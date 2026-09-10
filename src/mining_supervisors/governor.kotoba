(ns mining-supervisors.governor
  "MiningSupervisorGovernor — the independent safety/traceability layer for the
  ISCO-08 3121 mining shift supervisor administrative support actor. Wired as its own
  `:govern` node in `mining-supervisors.actor`'s StateGraph, downstream of `:advise`
  — the Advisor has no notion of supervisor/site provenance or high-risk operations,
  so this MUST be a separate system able to reject a proposal (itonami actor
  pattern, per ADR-2607011000 / CLAUDE.md Actors section).

  CRITICAL DOMAIN NOTE: This actor supports a MINING SHIFT SUPERVISOR's administrative
  workflow — shift logging, crew task assignment coordination, maintenance scheduling,
  safety flagging. It does NOT make extraction decisions, authorize blasting, set
  production targets, or determine mine-safety authority. Those remain the SUPERVISOR/
  OPERATOR's exclusive authority. Scope boundaries:
    ✓ Shift status reporting and logging
    ✓ Crew task assignment coordination
    ✓ Equipment maintenance scheduling
    ✓ Safety concern flagging (always escalated)
    ✗ Extraction/sequencing decisions
    ✗ Blasting authorization
    ✗ Production targeting/quotas
    ✗ Mine-safety authority determinations
    ✗ Equipment operation sequencing

  `check` is a pure function of (request, context, proposal, store) ->
  verdict; it never mutates the store. The StateGraph's `:decide` node
  routes on the verdict:
    :hard? true                → :hold  (irreversible, no write)
    :escalate? true            → :request-approval (interrupt-before)
    otherwise                  → :commit

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. supervisor provenance   — the request's supervisor must be registered.
    2. site provenance         — ops must involve a registered mine-site.
    3. no-actuation            — proposal :effect must be :propose.
    4. no-extraction           — any proposal flagged :op with operator-class
                               ops (:extract/:blast/:set-production-target/
                               :mine-safety-auth/:equipment-sequence) is an
                               instant hard block with no override path.

  ESCALATION invariants (:escalate? true, ALWAYS human sign-off):
    5. :op :flag-safety-concern — ALL safety concerns escalate.
    6. high-risk site          — any operation on a site flagged :risk-level :high
                               escalates.
    7. low confidence          — < `confidence-floor`."
  (:require [mining-supervisors.store :as store]))

(def confidence-floor 0.6)

;; Ops that are hard-blocked (not in supervisor's domain; operator/supervisor exclusive)
(def ^:private operator-class-ops
  #{:extract :blast :set-production-target :mine-safety-auth
    :equipment-sequence :ventilation-auth :ore-grade-assess})

;; Ops that always escalate
(def ^:private always-escalate-ops #{:flag-safety-concern})

(defn- hard-violations [{:keys [proposal site-id]} supervisor-record site-record]
  (cond-> []
    (nil? supervisor-record)
    (conj {:rule :no-supervisor :detail "unregistered supervisor"})

    (and site-id (nil? site-record))
    (conj {:rule :no-site :detail "unregistered mine-site"})

    (not= :propose (:effect proposal))
    (conj {:rule :no-actuation :detail "effect must be :propose only (no direct write)"})

    (contains? operator-class-ops (:op proposal))
    (conj {:rule :operator-class-blocked
           :detail "extraction/blasting/targeting/mine-safety ops are OPERATOR/SUPERVISOR-exclusive; not in supervisor's administrative scope"})))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `mining-supervisors.store/Store`. Returns
  `{:ok? bool :violations [...] :confidence n :hard? bool :escalate? bool}`."
  [request context proposal store]
  (let [supervisor-record (store/supervisor store (:supervisor-id request))
        site-id (get-in request [:site :site-id])
        site-record (when site-id (store/mine-site store site-id))
        hard (hard-violations {:proposal proposal :site-id site-id} supervisor-record site-record)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        safety-op? (contains? always-escalate-ops (:op proposal))
        high-risk? (and site-record (= :high (:risk-level site-record)))]
    {:ok? (and (not hard?) (not low?) (not safety-op?) (not high-risk?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? safety-op? high-risk?))}))
