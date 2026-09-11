# cloud-itonami-isco-3121

Open Occupation Blueprint for **ISCO-08 3121**: Mining Shift Supervisors.

This repository designs a forkable OSS business for a mining shift supervisor: a coordination robot performs shift reporting, crew assignment coordination, and maintenance scheduling under a governor-gated actor, so the supervisor maintains independent shift records and safety flagging instead of renting a closed mine-supervision SaaS.

## Critical domain boundary: ADMINISTRATIVE SUPPORT, NOT OPERATIONAL AUTHORITY

**This actor supports a MINING SHIFT SUPERVISOR's administrative workflow — NOT extraction decisions, production targeting, blasting authorization, or mine-safety authority.**

Scope: Shift supervisor-side administrative operations
- ✓ Shift status reporting and logging
- ✓ Crew task assignment coordination
- ✓ Equipment maintenance scheduling
- ✓ Safety concern flagging (always escalated to human review)

Out of scope: Operator/Supervisor-exclusive decisions (hard-blocked, no override path)
- ✗ Extraction sequencing or authorization (ore type/volume decisions)
- ✗ Blasting operations (powder type, load size, timing, sequence)
- ✗ Production targeting or quota setting
- ✗ Mine-safety determinations (ventilation adequacy, gas levels, hazard classification)
- ✗ Equipment operation authorization or sequencing

This boundary is enforced as a hard invariant in the governor (`mining-supervisor-governor`): any proposal flagged with operator-class ops (`:extract`, `:blast`, `:set-production-target`, `:mine-safety-auth`, etc.) is instantly blocked with no override path.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here a coordination robot performs shift logging,
crew assignment coordination, and maintenance scheduling under an actor that proposes
actions and an independent **Mining Supervisor Governor** that gates them.
The governor never dispatches operations itself; `:flag-safety-concern`
logging or `:high`-risk site dispatch require human sign-off. Extraction/blasting/
production-targeting/mine-safety decisions are operator/supervisor-exclusive and 
permanently blocked.

## Core Contract

```text
shift status + crew assignment request + maintenance plan
        |
        v
Supervisor Advisor -> Supervisor Governor -> logging/coordination/assignment, or human sign-off
        |
        v
coordination actions (gated) + shift records + audit ledger
```

No automated advice can dispatch an operation the governor refuses, suppress
an operating record, or disclose sensitive data without governor approval and
audit evidence.

## Capability layer

Resolves via [`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation)
(ISCO-08 `3121`). Required capabilities:

- :robotics
- :identity
- :forms
- :dmn
- :bpmn
- :audit-ledger

## Reference implementation (`:maturity :implemented`)

Full itonami Actor pattern (per ADR-2607011000 / CLAUDE.md's Actors
section): a REAL, compiled
[`kotoba-lang/langgraph`](https://github.com/kotoba-lang/langgraph)
`StateGraph`, with the Advisor and Governor as distinct graph nodes and
GENUINE human-in-the-loop interrupt/resume via checkpointing (not a
same-call `:outcome` relabel). An earlier version of this repo's
`build-graph` returned a plain `{:nodes :edges}` hashmap that was never
passed through the real `langgraph.graph` builder API — decorative
data, not an executable graph — and `run-request!` explicitly bypassed
even that fake graph with a hand-rolled threading pipeline, admitting
as much in its own comment ("Simplified stub: in a real implementation,
this would invoke langgraph.graph/state-graph"). `store/log-record!`
was declared and even referenced by the fake graph's `:commit` node map
entry, but `run-request!` never actually invoked that node — dead code
from the actor's point of view. `approve!` did not resume any real
checkpoint; it just relabelled a plain map's `:outcome` key. That gap
is now closed (`test/mining_supervisors/actor_test.kotoba`).

```text
:intake -> :advise -> :govern -> :decide -+-> :commit                        (:hard? false, :escalate? false)
                                           +-> :request-approval -> :commit    (:escalate? true, interrupt-before)
                                           +-> :hold                          (:hard? true)
```

- `src/mining_supervisors/store.kotoba` — `Store` protocol + `MemStore` +
  `DatomicStore` (via [`kotoba-lang/langchain-store`](https://github.com/kotoba-lang/langchain-store),
  no hand-rolled EDN-blob codec): registered supervisors/mine-sites, and
  the append-only audit ledger (`log-record!`/`get-audit-log`). Both
  backends pass the same contract
  (`test/mining_supervisors/store_contract_test.kotoba`).
- `src/mining_supervisors/advisor.kotoba` — `Advisor` protocol; `mock-advisor`
  (deterministic, default) proposes a supervisory operation from a
  request; `llm-advisor` wraps a `langchain.model/ChatModel` — either
  way the advisor only ever produces a `:propose`-effect proposal,
  never a committed record, and LLM parse failures always yield
  `:confidence 0.0` (forces escalation, never fabricated confidence).
  Its EDN response parsing now qualifies `read-string` to
  `clojure.edn/read-string` (`:clj`) / `cljs.reader/read-string`
  (`:cljs`) via a reader-conditional — the bare `read-string` it
  previously called does not exist in `cljs.core` at all (only in
  `cljs.reader`), a fleet-wide cljs-portability bug.
- `src/mining_supervisors/governor.kotoba` — `MiningSupervisorGovernor/check`: a pure
  function, wired as its own `:govern` node. Hard invariants
  (unregistered supervisor, unregistered mine-site, a proposal whose `:effect`
  isn't `:propose`, or any operator-class op) always route to `:hold` —
  and take priority over any simultaneous escalation reason. Escalation
  invariants (`:flag-safety-concern`, high-risk site operations, or low advisor
  confidence) always route to `:request-approval` — a genuine
  `interrupt-before` node the compiled graph pauses at (checkpointed) and
  only resumes past on explicit human approval (`actor/approve!`, which
  re-enters the SAME compiled graph via its own
  `:request-approval -> :commit` edge).
- `src/mining_supervisors/actor.kotoba` — `build-graph`, `run-request!`,
  `approve!`: the REAL `langgraph.graph/state-graph` wiring
  (`state-graph`/`add-node`/`add-edge`/`add-conditional-edges`/
  `compile-graph`). BOTH `:commit` and `:hold` durably append to the
  real audit ledger (`store/log-record!`) — previously `log-record!` was
  dead code from this actor's point of view, referenced only by the
  fake graph's node map, never actually invoked.

```bash
kbb -M:lint       # clj-kondo, 0 errors
kbb -M:dev:test    # 19 tests / 102 assertions, green
```

This is what backs this repo's `:maturity :implemented` entry in
[`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation).

## License

AGPL-3.0-or-later.
