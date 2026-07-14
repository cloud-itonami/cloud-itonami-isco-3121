(ns mining-supervisors.store
  "Store protocol and in-memory implementation for mining shift supervisor administrative
  support. Maintains registered supervisors, registered mine-sites, committed
  shift records, and an append-only audit ledger.

  The Store is the system of record and never exposed to the Advisor; only the
  Governor and Actor have store access (itonami actor pattern).")

(defprotocol Store
  "Operational store for mining shift supervisor administrative support."

  (supervisor [store supervisor-id]
    "Returns the registered supervisor record or nil.")

  (register-supervisor! [store record]
    "Registers a new supervisor {supervisor-id, name, certifications, experience-years, shift}.
     Returns the updated store.")

  (mine-site [store site-id]
    "Returns the registered mine-site record or nil.")

  (register-site! [store record]
    "Registers a new mine-site {site-id, operator-id, location, risk-level}.
     Returns the updated store.")

  (log-record! [store record-type record-data]
    "Appends a shift record (shift report, crew assignment, maintenance, incident).
     Always append-only; returns updated store.")

  (get-audit-log [store]
    "Returns the full append-only audit ledger (immutable vector)."))

(defrecord MemStore [a]
  Store
  (supervisor [_ supervisor-id]
    (get-in @a [:supervisors supervisor-id]))

  (register-supervisor! [s record]
    (swap! a assoc-in [:supervisors (:supervisor-id record)] record) s)

  (mine-site [_ site-id]
    (get-in @a [:mine-sites site-id]))

  (register-site! [s record]
    (swap! a assoc-in [:mine-sites (:site-id record)] record) s)

  (log-record! [s record-type record-data]
    (let [entry {:timestamp #?(:clj (java.time.Instant/now)
                               :cljs (js/Date.))
                 :type record-type
                 :data record-data}]
      (swap! a update :audit-log (fnil conj []) entry) s))

  (get-audit-log [_]
    (:audit-log @a)))

(defn mem-store
  ([] (mem-store {}))
  ([seed]
   (->MemStore (atom (merge {:supervisors {} :mine-sites {} :audit-log []} seed)))))
