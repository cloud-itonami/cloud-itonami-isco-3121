(ns mining-supervisors.store-contract-test
  "MemStore ≡ DatomicStore parity for the Store protocol — proves the
  backend swap (ADR-2607011000 injection boundary) is real: the same
  sequence of operations against either backend produces the same
  observable results. Mirrors `nco-admin.store-contract-test`
  (cloud-itonami-isco-0210). `DatomicStore` did not exist before this
  pass; `MemStore` was the only backend."
  (:require [clojure.test :refer [deftest is testing]]
            [mining-supervisors.store :as store]))

(defn- exercise
  "Runs the same op sequence against `s`, reading back through whatever
  store the LAST op returned -- deliberately mirrors
  `nco-admin.store-contract-test`'s `exercise` shape/comment even
  though, unlike `nco-admin.store`'s persistent/immutable `MemStore`,
  BOTH backends here (`MemStore`'s atom, `DatomicStore`'s conn) mutate
  in place and return the SAME store object. Binding+reading through
  the threaded result (rather than the original `s`) keeps this test
  correct even if a future backend stopped doing that."
  [s]
  (let [s (-> s
              (store/register-supervisor! {:supervisor-id "supervisor-1" :name "Bob Martinez"
                                            :certifications ["MSHA"] :experience-years 8 :shift "A"})
              (store/register-site! {:site-id "site-001" :operator-id "operator-1"
                                      :location "Nevada" :risk-level :medium})
              (store/log-record! :proposal-committed {:supervisor-id "supervisor-1"
                                                        :op :log-shift-report})
              (store/log-record! :proposal-held {:supervisor-id "no-such-supervisor"
                                                  :violations [{:rule :no-supervisor}]}))]
    {:supervisor (store/supervisor s "supervisor-1")
     :site (store/mine-site s "site-001")
     :absent-supervisor (store/supervisor s "no-such-supervisor")
     :absent-site (store/mine-site s "no-such-site")
     :ledger (store/get-audit-log s)}))

(deftest mem-and-datomic-parity
  (testing "same operations against MemStore and DatomicStore observe the same results"
    (let [mem (exercise (store/mem-store))
          dat (exercise (store/datomic-store))]
      (is (= "Bob Martinez" (:name (:supervisor mem))))
      (is (= "Bob Martinez" (:name (:supervisor dat))))
      (is (= ["MSHA"] (:certifications (:supervisor mem))))
      (is (= ["MSHA"] (:certifications (:supervisor dat))))
      (is (= "Nevada" (:location (:site mem))))
      (is (= "Nevada" (:location (:site dat))))
      (is (= :medium (:risk-level (:site mem))))
      (is (= :medium (:risk-level (:site dat))))
      (is (nil? (:absent-supervisor mem)))
      (is (nil? (:absent-supervisor dat)))
      (is (nil? (:absent-site mem)))
      (is (nil? (:absent-site dat)))
      (is (= 2 (count (:ledger mem))))
      (is (= 2 (count (:ledger dat))))
      (is (= :proposal-committed (:type (first (:ledger mem)))))
      (is (= :proposal-committed (:type (first (:ledger dat)))))
      (is (= :proposal-held (:type (second (:ledger mem)))))
      (is (= :proposal-held (:type (second (:ledger dat)))))
      (is (= "supervisor-1" (get-in (first (:ledger mem)) [:data :supervisor-id])))
      (is (= "supervisor-1" (get-in (first (:ledger dat)) [:data :supervisor-id])))
      (is (some? (:timestamp (first (:ledger mem)))))
      (is (some? (:timestamp (first (:ledger dat)))))
      (is (number? (:timestamp (first (:ledger dat)))) "timestamp round-trips through the EDN blob as a plain number"))))

(deftest datomic-store-nil-lookups
  (testing "unregistered supervisor/site lookups are nil on the DatomicStore too, and the ledger starts empty"
    (let [dat (store/datomic-store)]
      (is (nil? (store/supervisor dat "no-such-supervisor")))
      (is (nil? (store/mine-site dat "no-such-site")))
      (is (empty? (store/get-audit-log dat))))))

(deftest datomic-store-log-record-appends-in-order
  (testing "log-record! on DatomicStore preserves append order across multiple writes (seq-keyed event stream)"
    (let [dat (store/datomic-store)]
      (store/log-record! dat :a {:n 1})
      (store/log-record! dat :b {:n 2})
      (store/log-record! dat :c {:n 3})
      (is (= [:a :b :c] (mapv :type (store/get-audit-log dat))))
      (is (= [1 2 3] (mapv (comp :n :data) (store/get-audit-log dat)))))))
