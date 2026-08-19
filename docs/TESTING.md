# Testing approach, limitations, and trade-offs

## Testing approach

This prototype has a full automated test suite covering both the target
service and the orchestration engine - every template-set variant has its
own tests, and the orchestrator engine has 13 tests of its own. It went
through a rockier history than that one sentence suggests: removed
entirely, found to contradict the assignment's own requirements, and
restored in stages - see
[SUMMARY.md](SUMMARY.md#a-later-simplification-and-why-it-was-partly-reversed)
for that history stated plainly.

**1. URL shortener tests, written and run by the pipeline itself.** Each
template set ships a small, targeted test file that `TestingAgent` writes
into `url-shortener/src/test/` before actually running `mvn test` as a
subprocess - not a hand-authored suite frozen once and left static.

- `greenfield-test` (7 tests total - 2 `Base62Test` + 5 `UrlShortenerServiceTest`):
  shorten/resolve round-trip, invalid/blank URL rejection, expired-link
  resolution, unique code generation.
- `brownfield-bugfix-only-test` (7 tests total - `Base62Test` bootstrapped
  plus 5 in `UrlShortenerServiceTest`, one of which is
  **`expiredLinkClickIsNotCounted`** - the test that actually matters here.
  It fails against the pre-fix greenfield code (which increments the click
  counter before checking expiry) and passes once
  `UrlShortenerService.resolve()` is patched.
- `brownfield-test` (9 tests total): the bugfix suite plus two tests for
  the custom-alias feature (honored + duplicate-rejected, malformed-alias
  rejected).
- `ambiguous-test` (8 tests total - the greenfield suite bootstrapped, plus
  `ShortUrlRecordTest.resolvingSetsLastAccessedAt`): asserts the clarified
  analytics requirement actually did what was agreed - `lastAccessedAt`
  gets populated on resolve.

Since `ambiguous-test` introduces a filename (`ShortUrlRecordTest.java`)
none of the other sets use, `TestingAgent` deletes any test file left over
from a *different* scenario's run before writing its own set - otherwise a
brownfield run right after an ambiguous one would try to compile
`ShortUrlRecordTest.java` against a `shorten()` signature it wasn't written
for (the same class of bug described in
[SCENARIOS.md](SCENARIOS.md#why-every-run-re-derives-the-baseline-first)).
Verified in both directions: ambiguous → brownfield and brownfield →
ambiguous both leave the test directory in the correct state for whichever
scenario ran last.

**2. Build/test verification as the "testing" stage.** `TestingAgent`'s
`mvn test` run is what `ReleaseReadinessAgent`'s "green build" gate and the
`tests-required-for-new-endpoints` policy rule both key off - a stage
can't be marked release-ready without tests existing and actually passing.

**3. Live scenario runs as end-to-end tests on top of that.** Each of the
three scenarios generates real source and test files and runs a real `mvn
test`, so a scenario declaring success is evidence the whole path (codegen
→ compile → test → release gate) actually worked, not just that the unit
tests in isolation passed.

**4. Orchestrator engine tests, isolated from the URL shortener domain.**
`OrchestratorTest` (9 tests) and `DependencyGraphTest` (4 tests) use a
synthetic `TestStage` to prove the engine mechanics directly and in
milliseconds: parallel-level execution, retry-then-succeed, retry
exhaustion, fallback, rollback + safe-stop, entry-gate failure, denied
static approval, an independent branch surviving a sibling's failure,
re-plan scoping, cycle detection, and unknown-dependency rejection.

A few of these are the *only* proof of their mechanism anywhere in the
project - most notably `fallbackRescuesAFatallyFailedStage` (no agent in
any live scenario ever configures a fallback) and
`independentBranchKeepsRunningWhenSiblingFails` (the fixed 6-stage graph
has no topology where a live run could ever demonstrate this - `testing`
and `documentation` share one dependency rather than depending on each
other). The rest overlap with what a live scenario run also shows
(parallel execution, retry, rollback, re-plan) - kept anyway for fast,
deterministic, domain-independent coverage of the actual "critical
differentiator" this assignment asks for.

Run everything: `mvn test` from the repo root builds both modules and runs
all 13 orchestrator tests. Run any scenario (see [SETUP.md](SETUP.md)) to
see the url-shortener tests get written and executed for real as part of
the pipeline.

## Other limitations

- **Agents are deterministic templates by default, not live model calls.**
  Every agent implements `Stage.execute(ctx)` with fixed logic standing in
  for what an LLM-backed executor would produce for the same normalized
  input. This was a deliberate scope choice (see [SUMMARY.md](SUMMARY.md))
  so the deliverable is fully offline-reproducible and the orchestration
  mechanics are the thing actually being exercised, not prompt quality.
  `RequirementsAgent` is the one exception: given an `LlmClient`
  (`--use-claude`, see [SETUP.md](SETUP.md#using-real-claude-ai-optional)),
  it calls the real Claude API to judge ambiguity instead of the keyword
  heuristic, falling back to the heuristic if the call fails. The other
  five agents are still template-only - the seam is there
  (`Stage.execute()` takes `ExecutionContext` in, returns `StageResult`
  out) but nothing plugs into it for them yet.
- **Re-planning is manually triggered, not automatically detected.**
  `BrownfieldScenario` decides *when* to call `orchestrator.replan(...)`
  because it knows scope changed. A production system would want to detect
  "an upstream artifact changed" automatically (e.g. diffing normalized
  specs between runs) and trigger re-plan itself; that detection logic
  isn't built here.
- **In-memory persistence only.** `UrlStore` is an interface specifically
  so a real database could be swapped in without touching any HTTP handler,
  but only `InMemoryUrlStore` exists. Restarting the service loses all
  short links. This mirrors the URL shortener's own scope, not the
  orchestrator's design.
- **Rollback covers file writes only.** `ImplementationAgent.rollback()`
  restores the exact bytes each file had before the stage ran. It doesn't
  (and can't cheaply) undo side effects of `mvn test` itself (compiled
  `target/` output, etc.) - those are regenerated on the next successful
  run, not actively cleaned up on failure.
- **Rate limiting and the audit log are single-process.** The URL
  shortener's `RateLimiter` is in-memory per JVM instance, not distributed;
  `AuditLogger` writes to a local file, not a shared/durable log store.
  Fine for a prototype, not for a multi-instance deployment.
- **Policy engine ships three illustrative rules**, not a production
  ruleset (no SAST, license scanning, dependency vulnerability checks). The
  point demonstrated is that policy is centrally owned and enforced at
  every stage's exit gate, independent of the stage's own logic - adding a
  real rule is a five-line `PolicyRule.of(...)` registration, not an
  architecture change.
- **MTTR is intra-run only.** `MetricsCollector` computes mean-time-to-recover
  from a stage's first failure to its eventual success *within a single
  run's audit log* - it doesn't track recovery across separate runs or
  persist historical trend data.
- **Live LLM integration is opt-in and narrow.** Only `RequirementsAgent`
  can call Claude, and only for one decision (ambiguous or not, and what
  the interpretation should be) - it doesn't drive what code
  `ImplementationAgent` generates, which is still a fixed template per
  scenario regardless of what Claude decided. "Agentic" for the other five
  stages still refers to the orchestration/governance pattern, not
  AI-driven reasoning.

## Trade-offs made deliberately

- **Zero runtime dependencies** in the url-shortener module (no Spring
  Boot, no Jackson) in exchange for hand-rolled HTTP handlers and a minimal
  flat-JSON parser (`JsonUtil`). This guarantees the service builds and
  runs with just a JDK, at the cost of not looking like a typical
  production Java service. Explicitly a prototype-speed choice, not a
  recommendation for a real deployment.
- **Template-set agents over a live LLM integration.** Wiring a real model
  call would have made the demo non-reproducible (different output every
  run, dependent on an API key/network) and shifted evaluation weight onto
  prompt engineering instead of the orchestration engine, which is the
  actual "critical differentiator" this assignment asks for.
- **A fixed six-stage graph shared by all three scenarios**, rather than a
  different graph per scenario. This was chosen so the three scenarios are
  a fair comparison of *how the same governance model behaves* under
  different conditions (clear spec, changing scope, ambiguous spec) instead
  of three unrelated demos.
