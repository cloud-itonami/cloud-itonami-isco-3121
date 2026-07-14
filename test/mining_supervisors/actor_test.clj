(ns mining-supervisors.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [mining-supervisors.actor :as actor]
            [mining-supervisors.advisor :as advisor]
            [mining-supervisors.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-supervisor! st {:supervisor-id "supervisor-1" :name "Bob Martinez" :certifications ["MSHA"] :experience-years 8 :shift "A"})
    (store/register-site! st {:site-id "site-001" :operator-id "operator-1" :location "Nevada" :risk-level :medium})
    st))

(deftest graph-build-succeeds
  (let [st (fresh-store)
        advisor-fn (advisor/mock-advisor)
        store-fn (fn [] st)
        graph (actor/build-graph advisor-fn store-fn)]
    (is (contains? graph :nodes))
    (is (contains? graph :edges))
    (is (contains? graph :start-node))))

(deftest run-request-accepts-clean-proposal
  (let [st (fresh-store)
        adv (advisor/mock-advisor)
        request {:supervisor-id "supervisor-1" :op :log-shift-report :stake :low}
        state (actor/run-request! {:advisor adv} request {} st)]
    (is (contains? state :proposal))
    (is (contains? state :verdict))))

(deftest approve-marks-state-as-committed
  (let [state {:outcome :waiting-approval}
        approved (actor/approve! state)]
    (is (= :committed (:outcome approved)))
    (is (contains? approved :approval-timestamp))))
