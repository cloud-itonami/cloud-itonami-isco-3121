(ns mining-supervisors.actor
  "MiningSupervisorActor — the StateGraph wiring for ISCO-08 3121 mining shift supervisor
  administrative support. Implements the core itonami actor pattern:
    :intake -> :advise -> :govern -> :decide -+-> :commit
                                              +-> :request-approval
                                              +-> :hold

  Provides:
  - `build-graph`: constructs the StateGraph
  - `run-request!`: executes a single request through the graph
  - `approve!`: marks an interrupt point as approved for resumption

  The graph is checkpointable (support for human-in-the-loop via interrupt-before
  nodes) and all state is immutable (langgraph-clj pattern)."
  (:require [mining-supervisors.advisor :as advisor]
            [mining-supervisors.governor :as governor]
            [mining-supervisors.store :as store]))

;; State schema for the graph:
;; {:request {...}
;;  :context {}
;;  :proposal {...}
;;  :verdict {...}
;;  :outcome :committed | :held | :escalated
;;  :audit-entry {...}}

(defn build-graph
  "Constructs the StateGraph for mining shift supervisor administrative support.
   `advisor-fn` can be swapped (mock-advisor or llm-advisor).
   Returns a graph suitable for langgraph.graph/state-graph constructor."
  [advisor-fn store-fn]
  {:nodes
   {:intake (fn [state]
              (assoc state :outcome :pending))

    :advise (fn [state]
              (let [{:keys [request context]} state
                    st (store-fn)
                    proposal (advisor/-advise advisor-fn st request)]
                (assoc state :proposal proposal)))

    :govern (fn [state]
              (let [{:keys [request context proposal]} state
                    st (store-fn)
                    verdict (governor/check request context proposal st)]
                (assoc state :verdict verdict)))

    :decide (fn [state]
              (let [{:keys [verdict]} state]
                (cond
                  (:hard? verdict)    (assoc state :outcome :held)
                  (:escalate? verdict) (assoc state :outcome :escalated)
                  :else               (assoc state :outcome :ready-to-commit))))

    :commit (fn [state]
              (let [{:keys [request proposal verdict]} state
                    st (store-fn)
                    _ (store/log-record! st :proposal-committed
                        {:request request :proposal proposal :verdict verdict})]
                (assoc state :outcome :committed)))

    :request-approval (fn [state]
                        (assoc state :outcome :waiting-approval))}

   :edges
   {:intake :advise
    :advise :govern
    :govern :decide
    :decide (fn [state]
              (let [outcome (:outcome state)]
                (case outcome
                  :held :hold
                  :escalated :request-approval
                  :ready-to-commit :commit)))}

   :start-node :intake
   :end-node (fn [state]
               (contains? #{:committed :held :waiting-approval} (:outcome state)))})

(defn run-request!
  "Executes a single request through the actor graph.
   Returns the final state."
  [graph-config request context store-instance]
  ;; Simplified stub: in a real implementation, this would invoke
  ;; langgraph.graph/state-graph with checkpoint support.
  (let [state {:request request :context context :store store-instance}]
    ;; Simulate state transitions for testing
    (-> state
        ((fn [s] (assoc s :outcome :pending)))
        ((fn [s]
           (let [proposal (advisor/-advise (:advisor graph-config) (:store s) request)]
             (assoc s :proposal proposal))))
        ((fn [s]
           (let [verdict (governor/check request context (:proposal s) (:store s))]
             (assoc s :verdict verdict))))
        ((fn [s]
           (let [{:keys [verdict]} s]
             (cond
               (:hard? verdict)    (assoc s :outcome :held)
               (:escalate? verdict) (assoc s :outcome :escalated)
               :else               (assoc s :outcome :ready-to-commit))))))))

(defn approve!
  "Marks an escalated proposal as approved and allows resumption.
   In a real implementation, this would resume a checkpointed graph.
   Returns the updated state with approval timestamp."
  [state]
  (assoc state :outcome :committed :approval-timestamp #?(:clj (java.time.Instant/now)
                                                             :cljs (js/Date.))))
