(ns mining-supervisors.actor
  "MiningSupervisorActor — the ISCO-08 3121 mining shift supervisor
  administrative-support actor as a REAL `langgraph.graph/state-graph`
  (per ADR-2607011000 / CLAUDE.md Actors section). One graph run = one
  administrative request (intake → advise → govern → decide →
  commit/hold), with a GENUINE `interrupt-before` human-approval gate
  for escalated proposals — the graph pauses (checkpointed) at
  `:request-approval` and only continues on to `:commit` when a human
  operator explicitly resumes it via `approve!`.

  ```text
  :intake -> :advise -> :govern -> :decide -+-> :commit                        (:hard? false, :escalate? false)
                                             +-> :request-approval -> :commit    (:escalate? true, interrupt-before)
                                             +-> :hold                          (:hard? true)
  ```

  This replaces the previous `build-graph`, which built a plain
  `{:nodes :edges}` hashmap that was NEVER passed through the real
  `langgraph.graph` API (`state-graph`/`add-node`/`add-edge`/
  `add-conditional-edges`/`set-entry-point`/`set-finish-point`/
  `compile-graph`) — decorative data, not an executable graph — and the
  previous `run-request!`, which explicitly bypassed even that fake
  graph with a hand-rolled threading pipeline and an admitted stub
  comment (\"Simplified stub: in a real implementation, this would
  invoke langgraph.graph/state-graph\"). Mirrors `nco-admin.actor`
  (cloud-itonami-isco-0210, same fake-StateGraph-plus-dead-ledger
  problem, fixed prior to this pass).

  The unconditional invariant: the Mining Supervisor Advisor can never
  directly write a shift-supervisor record the Mining Supervisor
  Governor refuses — every `store/log-record!` call happens in
  `:commit`/`:hold`, reached only through `:decide`."
  (:require [langgraph.graph :as graph]
            [langgraph.checkpoint :as cp]
            [mining-supervisors.store :as store]
            [mining-supervisors.advisor :as advisor]
            [mining-supervisors.governor :as governor]))

(defn- intake-node
  "Intake node: pass the request through untouched."
  [state]
  state)

(defn- advise-node
  "Advise node: Advisor proposes an operation. Returns only the
  channel delta (langgraph folds partial updates through the channel
  reducers, see `build-graph`'s `:channels`). The Advisor protocol
  (`-advise [advisor store request]`) takes the store (read-only, for
  context lookups) but never writes to it."
  [advisor-instance store-instance {:keys [request]}]
  {:proposal (advisor/-advise advisor-instance store-instance request)})

(defn- govern-node
  "Govern node: Governor evaluates the proposal against the store,
  independently of the advisor."
  [store-instance {:keys [request context proposal]}]
  {:verdict (governor/check request context proposal store-instance)})

(defn- decide-node
  "Decide node: route based on governor verdict. Sets :disposition
  only — the conditional edge below reads it, no store write happens
  here."
  [{:keys [verdict]}]
  {:disposition (cond
                  (:hard? verdict)     :hold
                  (:escalate? verdict) :request-approval
                  :else                :commit)})

(defn- commit-node
  "Commit node: durably append the committed shift-supervisor record to
  the REAL audit ledger via `mining-supervisors.store/log-record!` —
  the core missing behavior this actor previously had (`log-record!`
  existed and was even referenced from the fake graph's `:commit` node
  map entry, but `run-request!` never actually invoked that node —
  dead code from this actor's point of view)."
  [store-instance {:keys [request proposal verdict]}]
  (store/log-record! store-instance :proposal-committed
                      {:supervisor-id (:supervisor-id request)
                       :proposal proposal
                       :verdict verdict})
  {:records [{:recorded true :op (:op proposal)}]})

(defn- request-approval-node
  "Request-approval node: the `interrupt-before` gate. When the graph
  actually reaches (executes) this node, it's because a human operator
  resumed the thread via `approve!` — interrupt-before pauses BEFORE
  this node runs on the first pass, so reaching its body at all means
  human sign-off already happened. Falls straight through to :commit
  via the graph's own `:request-approval -> :commit` edge."
  [_state]
  {})

(defn- hold-node
  "Hold node: a HARD governance violation. Never writes the proposed
  record, but DOES durably append a `:proposal-held` audit fact to the
  same ledger `:commit` uses — so a supervisor's full administrative
  history (including refused proposals) is always a query over the
  immutable ledger, not just the commits."
  [store-instance {:keys [request verdict]}]
  (store/log-record! store-instance :proposal-held
                      {:supervisor-id (:supervisor-id request)
                       :violations (:violations verdict)})
  {:records [{:held true :violations (:violations verdict)}]})

(defn build-graph
  "Build and compile the REAL `langgraph.graph` StateGraph for the
  mining shift supervisor actor, against `state-graph`/`add-node`/
  `add-edge`/`add-conditional-edges`/`set-entry-point`/
  `set-finish-point`/`compile-graph` — the actual exported API of
  `langgraph.graph`. `checkpointer` defaults to an in-memory one
  (`langgraph.checkpoint/mem-checkpointer`) so `:request-approval`
  interrupts are genuinely resumable via `approve!`.

  `advisor-instance`/`store-instance` are already-constructed instances
  (e.g. `(advisor/mock-advisor)` / `(store/mem-store)`), not thunks —
  since both `MemStore` and `DatomicStore` mutate their backing
  atom/conn in place and return the SAME store, a single shared
  instance closed over by every node observes every other node's
  writes without needing a `:store` graph channel to thread an updated
  value through."
  [advisor-instance store-instance & [{:keys [checkpointer]
                                        :or {checkpointer (cp/mem-checkpointer)}}]]
  (-> (graph/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}
         :records     {:reducer into :default []}}})

      (graph/add-node :intake intake-node)
      (graph/add-node :advise (partial advise-node advisor-instance store-instance))
      (graph/add-node :govern (partial govern-node store-instance))
      (graph/add-node :decide decide-node)
      (graph/add-node :commit (partial commit-node store-instance))
      (graph/add-node :request-approval request-approval-node)
      (graph/add-node :hold (partial hold-node store-instance))

      (graph/set-entry-point :intake)
      (graph/add-edge :intake :advise)
      (graph/add-edge :advise :govern)
      (graph/add-edge :govern :decide)

      (graph/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :commit           :commit
            :request-approval :request-approval
            :hold)))

      (graph/add-edge :request-approval :commit)

      (graph/set-finish-point :commit)
      (graph/set-finish-point :hold)

      (graph/compile-graph
       {:checkpointer     checkpointer
        :interrupt-before #{:request-approval}})))

(defn run-request!
  "Run one mining-supervisor administrative request through the REAL
  compiled actor graph (`compiled-graph` from `build-graph`) via
  `langgraph.graph/run*`. `thread-id` scopes checkpointing so an
  escalated (interrupted) run can be resumed by `approve!`. Returns the
  full run result: `{:state .. :events .. :status :done|:interrupted
  :frontier ..}` — `:status :interrupted` with `:frontier
  [:request-approval]` means the request is genuinely paused awaiting
  human sign-off, not merely a `:outcome` flag on an already-finished
  run."
  [compiled-graph request context thread-id]
  (graph/run* compiled-graph {:request request :context context}
              {:thread-id thread-id}))

(defn approve!
  "Human-in-the-loop resume: a human operator's approval of a request
  parked at `:request-approval` genuinely resumes the compiled graph
  (via `langgraph.graph/run*` with `:resume? true`), which runs the
  `:request-approval -> :commit` edge and so durably commits the
  record through the SAME `commit-node` a clean, non-escalated run
  uses — not a hand-rolled parallel commit path."
  [compiled-graph thread-id]
  (graph/run* compiled-graph nil {:thread-id thread-id :resume? true}))
