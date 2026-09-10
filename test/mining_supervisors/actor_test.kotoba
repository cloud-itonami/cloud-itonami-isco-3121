(ns mining-supervisors.actor-test
  "Integration tests for `mining-supervisors.actor` — builds the REAL
  compiled `langgraph.graph` and runs `run-request!`/`approve!`
  end-to-end through all the routes the `:decide` conditional edge can
  take (clean commit / hard hold / escalate-then-approve / hard beats
  escalate). This namespace did not previously exercise a real graph at
  all: `build-graph` returned a plain `{:nodes :edges}` hashmap that
  `run-request!` never actually passed through `langgraph.graph` — it
  bypassed even that fake graph with a hand-rolled threading pipeline
  (an admitted stub: \"Simplified stub: in a real implementation, this
  would invoke langgraph.graph/state-graph\"). The old test suite's
  `graph-build-succeeds` (`(contains? graph :nodes)` etc.) could not
  have caught either problem — it asserted shape, never behavior — and
  `run-request-accepts-clean-proposal` never checked that a record
  landed in the audit ledger at all. These tests close that gap and
  prove the audit ledger (`mining-supervisors.store/log-record!`) is
  genuinely wired into the `:commit`/`:hold` nodes, and that
  `:request-approval` is a genuine checkpointed interrupt, not a
  same-call `:outcome` relabel."
  (:require [clojure.test :refer [deftest is testing]]
            [mining-supervisors.actor :as actor]
            [mining-supervisors.advisor :as advisor]
            [mining-supervisors.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-supervisor! st {:supervisor-id "supervisor-1"
                                     :name "Bob Martinez"
                                     :certifications ["MSHA"]
                                     :experience-years 8
                                     :shift "A"})
    (store/register-site! st {:site-id "site-001" :operator-id "operator-1"
                               :location "Nevada" :risk-level :medium})
    st))

(deftest run-request-commits-clean-proposal
  (testing "a valid, high-confidence, low-stake shift report runs the
            real compiled graph end to end and reaches :done"
    (let [st (fresh-store)
          g (actor/build-graph (advisor/mock-advisor) st)
          result (actor/run-request! g {:supervisor-id "supervisor-1"
                                         :op :log-shift-report :stake :low}
                                      {} "thread-commit-1")
          state (:state result)]
      (is (= :done (:status result)))
      (is (= [{:recorded true :op :log-shift-report}] (:records state)))
      (is (false? (:hard? (:verdict state))))
      (is (not (:escalate? (:verdict state)))
          "governor.cljc's :escalate? can be `nil` rather than a literal
           `false` when no :site is referenced at all (the (or low?
           safety-op? high-risk?) with high-risk? itself nil short-circuits
           `and` to nil, not false) -- a pre-existing governor.cljc nuance,
           preserved exactly, so this asserts falsy via `not` the same way
           governor_test.clj's own `accepts-clean-low-stake-proposal` does,
           not `false?`")
      (testing "the commit is genuinely durable in the store's real audit
                ledger (`mining-supervisors.store/log-record!`), not just
                the transient graph-state `:records` mirror -- queried
                independently from the ORIGINAL store instance, which
                MemStore mutates in place"
        (let [ledger (store/get-audit-log st)]
          (is (= 1 (count ledger)))
          (is (= :proposal-committed (:type (first ledger))))
          (is (= "supervisor-1" (get-in (first ledger) [:data :supervisor-id])))
          (is (some? (:timestamp (first ledger)))))))))

(deftest run-request-holds-unregistered-supervisor
  (testing "an unregistered supervisor is a HARD violation -- the real
            graph routes to :hold and terminates, never :commit"
    (let [st (fresh-store)
          g (actor/build-graph (advisor/mock-advisor) st)
          result (actor/run-request! g {:supervisor-id "no-such-supervisor"
                                         :op :log-shift-report :stake :low}
                                      {} "thread-hold-1")
          state (:state result)]
      (is (= :done (:status result)))
      (is (true? (:hard? (:verdict state))))
      (is (= [{:held true :violations (:violations (:verdict state))}] (:records state))
          "no COMMITTED record -- only the transient :held audit trace")
      (testing "the HARD violation is ALSO durably recorded to the real
                audit ledger by the :hold node -- previously :hold's
                store write was unreachable code, `run-request!` never
                invoked the node function that contained it"
        (let [ledger (store/get-audit-log st)]
          (is (= 1 (count ledger)))
          (is (= :proposal-held (:type (first ledger))))
          (is (= "no-such-supervisor" (get-in (first ledger) [:data :supervisor-id])))
          (is (seq (get-in (first ledger) [:data :violations]))))))))

(deftest run-request-holds-operator-class-op-despite-high-confidence
  (testing "extraction is permanently out of scope (operator/supervisor
            exclusive) -- the real graph routes to :hold even though
            advisor confidence (0.7, stake :high) is well above the
            0.6 escalation floor, proving the hard-block short-circuits
            BEFORE the low-confidence check, not merely alongside it"
    (let [st (fresh-store)
          g (actor/build-graph (advisor/mock-advisor) st)
          result (actor/run-request! g {:supervisor-id "supervisor-1"
                                         :op :extract :stake :high}
                                      {} "thread-hold-2")
          state (:state result)]
      (is (= :done (:status result)))
      (is (= 0.7 (:confidence (:verdict state))))
      (is (true? (:hard? (:verdict state))))
      (is (true? (:held (first (:records state)))))
      (let [ledger (store/get-audit-log st)]
        (is (= 1 (count ledger)))
        (is (= :proposal-held (:type (first ledger))))))))

(deftest run-request-escalates-safety-concern-then-approve-commits
  (testing "flag-safety-concern ALWAYS escalates -- the real graph
            GENUINELY interrupts (checkpointed) at :request-approval
            and stops there; a human approve! resumes the SAME
            compiled graph and commits the record via the actual
            :request-approval -> :commit edge, not a hand-rolled
            parallel commit path"
    (let [st (fresh-store)
          g (actor/build-graph (advisor/mock-advisor) st)
          held (actor/run-request! g {:supervisor-id "supervisor-1"
                                       :op :flag-safety-concern :stake :low}
                                    {} "thread-escalate-1")
          held-state (:state held)]
      (is (= :interrupted (:status held)))
      (is (= [:request-approval] (:frontier held)))
      (is (true? (:escalate? (:verdict held-state))))
      (is (false? (:hard? (:verdict held-state))))
      (is (empty? (:records held-state)) "not yet committed -- awaiting human sign-off")
      (is (empty? (store/get-audit-log st)) "the ORIGINAL store has no record yet either")
      (let [approved (actor/approve! g "thread-escalate-1")
            approved-state (:state approved)]
        (is (= :done (:status approved)))
        (is (= [{:recorded true :op :flag-safety-concern}] (:records approved-state)))
        (testing "approve! also genuinely persists to the real audit ledger"
          (let [ledger (store/get-audit-log st)]
            (is (= 1 (count ledger)))
            (is (= :proposal-committed (:type (first ledger))))))))))

(deftest run-request-escalates-high-risk-site-then-approve-commits
  (testing "an operation against a :risk-level :high site escalates
            even though the op and confidence are otherwise routine --
            a SEPARATE escalation path from flag-safety-concern,
            proving the conditional router reads the governor's
            :escalate? verdict generically rather than special-casing
            one op"
    (let [st (store/mem-store)
          _ (store/register-supervisor! st {:supervisor-id "supervisor-1"
                                             :name "Bob Martinez"
                                             :certifications ["MSHA"]
                                             :experience-years 8 :shift "A"})
          _ (store/register-site! st {:site-id "site-high-risk" :operator-id "operator-1"
                                       :location "Nevada" :risk-level :high})
          g (actor/build-graph (advisor/mock-advisor) st)
          held (actor/run-request! g {:supervisor-id "supervisor-1"
                                       :site {:site-id "site-high-risk"}
                                       :op :log-shift-report :stake :low}
                                    {} "thread-escalate-2")
          held-state (:state held)]
      (is (= :interrupted (:status held)))
      (is (= [:request-approval] (:frontier held)))
      (is (true? (:escalate? (:verdict held-state))))
      (let [approved (actor/approve! g "thread-escalate-2")]
        (is (= :done (:status approved)))
        (is (= [{:recorded true :op :log-shift-report}] (:records (:state approved))))
        (is (= 1 (count (store/get-audit-log st))))))))

(deftest run-request-hard-violation-beats-escalation-holds
  (testing "a request that is BOTH a hard violation (unregistered
            mine-site) AND would otherwise escalate (flag-safety-concern)
            takes the :hold route, never :request-approval -- exercises
            mining-supervisors.governor's own stated priority (hard? beats
            escalate?, see governor.cljc's :escalate? computation gating
            on (not hard?)) through the ACTUAL compiled conditional edge,
            not just a governor unit test in isolation"
    (let [st (fresh-store)
          g (actor/build-graph (advisor/mock-advisor) st)
          result (actor/run-request! g {:supervisor-id "supervisor-1"
                                         :site {:site-id "no-such-site"}
                                         :op :flag-safety-concern :stake :low}
                                      {} "thread-priority-1")
          state (:state result)]
      (is (= :done (:status result)) "NOT :interrupted -- hard violations never reach :request-approval")
      (is (true? (:hard? (:verdict state))))
      (is (false? (:escalate? (:verdict state))))
      (is (true? (:held (first (:records state)))))
      (let [ledger (store/get-audit-log st)]
        (is (= 1 (count ledger)))
        (is (= :proposal-held (:type (first ledger))))
        (is (some #(= :no-site (:rule %)) (get-in (first ledger) [:data :violations])))))))
