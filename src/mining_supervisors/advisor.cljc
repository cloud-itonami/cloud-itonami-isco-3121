(ns mining-supervisors.advisor
  "MiningSupervisorAdvisor — proposes a mining supervisor's administrative operation
  (shift logging, crew assignment coordination, maintenance scheduling, or safety
  flagging) for a registered supervisor and mine-site. The advisor is swappable:
  `mock-advisor` (deterministic, default in dev/tests/CI) or `llm-advisor`
  (wraps a real `langchain.model/ChatModel`). Either way the advisor ONLY
  produces a PROPOSAL — it never writes to the store and has no notion of
  supervisor/site provenance or high-risk decisions; `mining-supervisors.governor`
  is the independent system that decides whether the proposal may proceed, per
  the itonami actor pattern.

  A proposal is a map:
    {:op :log-shift-report
         | :coordinate-crew-assignment
         | :coordinate-maintenance
         | :flag-safety-concern
     :effect :propose              ; advisor NEVER emits a raw store write
     :stake :low|:medium|:high
     :confidence 0.0-1.0
     :rationale str}

  LLM parse failures always yield `:confidence 0.0` (never fabricate
  confidence), which forces the governor to escalate/hold."
  (:require #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])))

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- infer
  "Deterministic mock inference: reads the request's declared op/stake
  straight through (a stand-in for what an LLM would extract from free
  text), with a stake-derived confidence."
  [_store {:keys [op stake] :as request}]
  {:op op
   :effect :propose
   :stake (or stake :low)
   :confidence (case (or stake :low) :high 0.7 :medium 0.85 :low 0.95)
   :rationale (str "proposed " (name op) " for supervisor " (:supervisor-id request))})

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are a mining shift supervisor's administrative advisor. Given an administrative
   operation request (shift logging, crew assignment coordination, maintenance
   scheduling, or safety flagging), propose an :op, an honest :confidence
   (0.0-1.0), and a :stake (:low/:medium/:high). Never fabricate confidence
   you don't have. You do NOT make extraction decisions, authorize blasting, set
   production targets, or determine mine-safety authority — those are supervisor/
   operator exclusive.")

(defn- parse-proposal
  "Parses the LLM's EDN response via `clojure.edn/read-string` (:clj) /
  `cljs.reader/read-string` (:cljs) -- NOT bare `read-string`, which
  resolves to `clojure.core/read-string` under :clj but does not exist
  in `cljs.core` at all (it lives only in `cljs.reader`), so this ns
  previously failed to compile under ClojureScript -- the fleet-wide
  cljs-portability bug (matches the reader-conditional `langchain-store.core`
  already uses for the same `edn/read-string` call)."
  [content]
  (try
    (let [p (edn/read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0 :stake :high
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0 :stake :high
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  "Wraps a `langchain.model/ChatModel`. `gen-opts` is passed through to
  `model/-generate`. Kept decoupled from any concrete model so this ns
  has no hard dependency beyond `langchain.model`'s protocol."
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "administrative request: " (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))
