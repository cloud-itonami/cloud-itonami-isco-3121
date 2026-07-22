(ns mining-supervisors.store
  "Store protocol and implementations for mining shift supervisor administrative
  support. Maintains registered supervisors, registered mine-sites, committed
  shift records, and an append-only audit ledger.

  The Store is the system of record and never exposed to the Advisor; only the
  Governor and Actor have store access (itonami actor pattern).

  Two backends implement the same `Store` protocol so the backend is a swap,
  not a rewrite (the itonami actor pattern's injection boundary,
  ADR-2607011000; mirrors `nco-admin.store`, cloud-itonami-isco-0210 — the
  same fake-StateGraph-plus-dead-ledger problem, fixed prior to this pass):

    - `MemStore`     — an atom-backed in-memory implementation. The
                       deterministic default for dev/tests/demo (no deps);
                       mutator methods mutate the shared atom in place and
                       return the SAME store (their record identity never
                       changes, so callers never need to re-bind the result
                       the way an immutable-record store would require).
    - `DatomicStore` — backed by `langchain.db`, a Datomic-API-compatible
                       EAV store (swappable to a kotoba-server pod in
                       production). Supervisor/site records and ledger
                       entries carry free-form fields, so each is stored as
                       an EDN-blob payload via `langchain-store.core`
                       (`ls/enc`/`ls/dec*`), not a hand-rolled codec
                       (ADR-2607141600). Mutator methods also mutate the
                       conn in place and return the SAME store.

  Both pass the same contract (test/mining_supervisors/store_contract_test.cljc).

  `log-record!`/`get-audit-log` is the append-only audit ledger:
  `mining-supervisors.actor`'s `:commit`/`:hold` graph nodes append every
  committed shift-supervisor operation AND every hard-governance-violation
  hold fact here, so a supervisor's full administrative history (committed or
  held) is always a query over an immutable log. Prior to this pass,
  `log-record!` was declared but never invoked by `actor/run-request!`'s
  actual execution path (the fake StateGraph's `:commit` node referenced it,
  but `run-request!` bypassed that node map entirely with a hand-rolled
  threading pipeline) — dead code from the actor's point of view."
  (:require [langchain.db :as d]
            [langchain-store.core :as ls]))

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

(defn- now-ms
  "Plain epoch-millis, not a host `Instant`/`Date` object: `DatomicStore`
  stores ledger entries as `pr-str`'d EDN blobs (`langchain-store.core`'s
  `enc`/`dec*` convention) and neither `java.time.Instant` nor `js/Date`
  round-trips through `pr-str`/`edn/read-string` (no EDN reader for either
  tag), so `MemStore` uses the same portable representation for parity
  with `DatomicStore` (the store-contract test asserts both backends
  behave identically, not just that each independently has SOME
  timestamp)."
  []
  #?(:clj (System/currentTimeMillis)
     :cljs (.getTime (js/Date.))))

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
    (let [entry {:timestamp (now-ms) :type record-type :data record-data}]
      (swap! a update :audit-log (fnil conj []) entry))
    s)

  (get-audit-log [_]
    (:audit-log @a)))

(defn mem-store
  ([] (mem-store {}))
  ([seed]
   (->MemStore (atom (merge {:supervisors {} :mine-sites {} :audit-log []} seed)))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  `:supervisor/payload`/`:site/payload` are opaque EDN-string blobs (via
  `langchain-store.core`) so `langchain.db` doesn't try to expand a
  caller-defined record into sub-entities — same convention as
  `nco-admin.store`'s `:nco/payload`/`:unit/payload`."
  (ls/identity-schema [:supervisor/id :site/id :record/seq]))

(defn- blob-lookup
  "Look up the EDN-blob payload for the entity uniquely identified by
  `id-attr`/`id` and stored under `payload-attr`."
  [conn id-attr payload-attr id]
  (when id
    (ls/dec* (d/q {:find '[?p .] :in '[$ ?id]
                   :where [['?e id-attr '?id] ['?e payload-attr '?p]]}
                  (d/db conn) id))))

(defrecord DatomicStore [conn]
  Store
  (supervisor [_ supervisor-id]
    (blob-lookup conn :supervisor/id :supervisor/payload supervisor-id))

  (register-supervisor! [s record]
    (d/transact! conn [{:supervisor/id (:supervisor-id record)
                         :supervisor/payload (ls/enc record)}])
    s)

  (mine-site [_ site-id]
    (blob-lookup conn :site/id :site/payload site-id))

  (register-site! [s record]
    (d/transact! conn [{:site/id (:site-id record)
                         :site/payload (ls/enc record)}])
    s)

  (log-record! [s record-type record-data]
    (let [entry {:timestamp (now-ms) :type record-type :data record-data}]
      (ls/append-blob! conn :record/seq :record/payload (count (get-audit-log s)) entry))
    s)

  (get-audit-log [_]
    (ls/read-stream conn :record/seq :record/payload)))

(defn datomic-store
  "Create a new DatomicStore (langchain.db-backed) for mining supervisor
  admin records — the production-shaped backend for the same `Store`
  protocol `mem-store`'s `MemStore` implements."
  []
  (->DatomicStore (d/create-conn schema)))
