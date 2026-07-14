(ns mining-supervisors.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [mining-supervisors.governor :as governor]
            [mining-supervisors.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-supervisor! st {:supervisor-id "supervisor-1" :name "Bob Martinez" :certifications ["MSHA" "PMP"] :experience-years 8 :shift "A"})
    (store/register-site! st {:site-id "site-001" :operator-id "operator-1" :location "Nevada" :risk-level :medium})
    st))

(deftest accepts-clean-low-stake-proposal
  (let [st (fresh-store)
        request {:supervisor-id "supervisor-1"}
        proposal {:op :log-shift-report :effect :propose :confidence 0.95}
        verdict (governor/check request {} proposal st)]
    (is (:ok? verdict))
    (is (not (:hard? verdict)))
    (is (not (:escalate? verdict)))))

(deftest hard-blocks-unregistered-supervisor
  (let [st (fresh-store)
        request {:supervisor-id "no-such-supervisor"}
        proposal {:op :log-shift-report :effect :propose :confidence 0.95}
        verdict (governor/check request {} proposal st)]
    (is (not (:ok? verdict)))
    (is (:hard? verdict))
    (is (seq (:violations verdict)))))

(deftest hard-blocks-extraction-operation
  (let [st (fresh-store)
        request {:supervisor-id "supervisor-1"}
        proposal {:op :extract :effect :propose :confidence 0.95}
        verdict (governor/check request {} proposal st)]
    (is (not (:ok? verdict)))
    (is (:hard? verdict))
    (is (seq (:violations verdict)))))

(deftest hard-blocks-blasting-operation
  (let [st (fresh-store)
        request {:supervisor-id "supervisor-1"}
        proposal {:op :blast :effect :propose :confidence 0.95}
        verdict (governor/check request {} proposal st)]
    (is (not (:ok? verdict)))
    (is (:hard? verdict))
    (is (seq (:violations verdict)))))

(deftest hard-blocks-production-targeting
  (let [st (fresh-store)
        request {:supervisor-id "supervisor-1"}
        proposal {:op :set-production-target :effect :propose :confidence 0.95}
        verdict (governor/check request {} proposal st)]
    (is (not (:ok? verdict)))
    (is (:hard? verdict))
    (is (seq (:violations verdict)))))

(deftest escalates-safety-concern
  (let [st (fresh-store)
        request {:supervisor-id "supervisor-1"}
        proposal {:op :flag-safety-concern :effect :propose :confidence 0.95}
        verdict (governor/check request {} proposal st)]
    (is (not (:ok? verdict)))
    (is (not (:hard? verdict)))
    (is (:escalate? verdict))))

(deftest escalates-high-risk-site-operation
  (let [st (store/mem-store)
        _ (store/register-supervisor! st {:supervisor-id "supervisor-1" :name "Bob Martinez" :certifications ["MSHA"] :experience-years 8 :shift "A"})
        _ (store/register-site! st {:site-id "site-high-risk" :operator-id "operator-1" :location "Nevada" :risk-level :high})
        request {:supervisor-id "supervisor-1" :site {:site-id "site-high-risk"}}
        proposal {:op :log-shift-report :effect :propose :confidence 0.95}
        verdict (governor/check request {} proposal st)]
    (is (not (:ok? verdict)))
    (is (not (:hard? verdict)))
    (is (:escalate? verdict))))

(deftest escalates-low-confidence-proposal
  (let [st (fresh-store)
        request {:supervisor-id "supervisor-1"}
        proposal {:op :coordinate-crew-assignment :effect :propose :confidence 0.5}
        verdict (governor/check request {} proposal st)]
    (is (not (:ok? verdict)))
    (is (not (:hard? verdict)))
    (is (:escalate? verdict))))

(deftest hard-blocks-unregistered-site
  (let [st (store/mem-store)
        _ (store/register-supervisor! st {:supervisor-id "supervisor-1" :name "Bob Martinez" :certifications ["MSHA"] :experience-years 8 :shift "A"})
        request {:supervisor-id "supervisor-1" :site {:site-id "unknown-site"}}
        proposal {:op :coordinate-maintenance :effect :propose :confidence 0.95}
        verdict (governor/check request {} proposal st)]
    (is (not (:ok? verdict)))
    (is (:hard? verdict))
    (is (seq (:violations verdict)))))

(deftest hard-blocks-non-propose-effect
  (let [st (fresh-store)
        request {:supervisor-id "supervisor-1"}
        proposal {:op :log-shift-report :effect :commit :confidence 0.95}
        verdict (governor/check request {} proposal st)]
    (is (not (:ok? verdict)))
    (is (:hard? verdict))
    (is (seq (:violations verdict)))))
