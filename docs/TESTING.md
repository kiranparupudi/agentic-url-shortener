# Testing approach, limitations, and trade-offs

## Testing approach

This prototype has a **deliberately minimal** automated test suite, not an
exhaustive one - restored after being removed and then reinstated once it
became clear the removal contradicted the assignment's own requirements
(unit/integration tests are explicitly listed under "Engineering Output
Generation", "testable" is a named evaluation criterion, and "Testing
approach" is a required deliverable section). See
[SUMMARY.md](SUMMARY.md#a-later-simplification-and-why-it-was-partly-reversed)
for that history stated plainly.

**1. URL shortener tests, written and run by the pipeline itself.** Each
template set ships a small, targeted test file that `TestingAgent` writes
into `url-shortener/src/test/` before actually running `mvn test` as a
subprocess - not a hand-authored suite frozen once and left static. Every
template set converges on the same two filenames
(`Base62Test.java`, `UrlShortenerServiceTest.java`), so there's no risk of
one scenario's test file becoming an orphan that breaks a different
scenario's build (a real bug from an earlier design - see
[SCENARIOS.md](SCENARIOS.md#why-every-run-re-derives-the-baseline-first)).

- `greenfield-test` (5 tests): shorten/resolve round-trip, invalid/blank URL
  rejection, expired-link resolution, unique code generation. Used by
  greenfield and ambiguous (ambiguous's delta doesn't touch `shorten()`'s
  signature, so the baseline suite is still valid against it).
- `brownfield-bugfix-only-test` (5 tests): the baseline suite, minus the
  uniqueness test, plus **`expiredLinkClickIsNotCounted`** - the one test
  that actually matters here. It fails against the pre-fix greenfield code
  (which increments the click counter before checking expiry) and passes
  once `UrlShortenerService.resolve()` is patched. This is the concrete
  "unit/integration test" proof the assignment's Engineering Output
  Generation requirement asks for.
- `brownfield-test` (7 tests): the bugfix suite plus two tests for the
  custom-alias feature (honored + duplicate-rejected, malformed-alias
  rejected).

**2. Build/test verification as the "testing" stage.** `TestingAgent`'s
`mvn test` run is what `ReleaseReadinessAgent`'s "green build" gate and the
`tests-required-for-new-endpoints` policy rule both key off - a stage
can't be marked release-ready without tests existing and actually passing.

**3. Live scenario runs as end-to-end tests on top of that.** Each of the
three scenarios generates real source and test files and runs a real `mvn
test`, so a scenario declaring success is evidence the whole path (codegen
→ compile → test → release gate) actually worked, not just that the unit
tests in isolation passed.

**4. Orchestrator engine tests, scoped to what nothing else proves.**
`orchestrator/src/test/java/.../core/OrchestratorTest.java` has exactly 5
tests, each covering a mechanism that no live scenario run ever exercises
(the three scenarios' happy/deny paths structurally never hit them):

- `exhaustedRetriesFailStageAndBlockDependent` - every live retry
  (brownfield) succeeds on attempt 2; retries actually running out is
  never shown live.
- `fallbackRescuesAFatallyFailedStage` - no agent in any scenario ever
  configures a fallback, so this is the only place the mechanism is
  proven at all.
- `entryGateFailureBlocksExecutionAndDependents` - every entry gate in all
  3 scenarios always passes in every captured run.
- `deniedApprovalSafeStopsWithoutExecuting` - the orchestrator's static
  `requiresApproval()` flag path (what `release-readiness` uses). Distinct
  from `RequirementsAgent`'s dynamic, agent-initiated approval request
  (which *is* shown denied live, via `--auto-deny`) - this is the other,
  untested approval path.
- `independentBranchKeepsRunningWhenSiblingFails` - structural: the fixed
  6-stage graph has no topology where an independent sibling and a
  dependent sibling sit alongside a failing stage in the same level
  (`testing`/`documentation` share one dependency, they don't depend on
  each other), so the live pipeline can never demonstrate this claim no
  matter how it's run.

The other 8 tests this project had before removal (`independentStagesInSameLevelBothSucceed`,
`recoverableFailureIsRetriedThenSucceeds`, `rollbackAndSafeStopWhenNoFallbackAvailable`,
`replanOnlyReExecutesTheAffectedSubgraph`, `groupsIndependentStagesIntoTheSameLevel`,
`detectsCycles`, `rejectsDependencyOnUnknownStage`,
`transitiveDependentsIncludesIndirectDescendants`) were deliberately **not**
restored - each either duplicates something a live scenario run already
demonstrates (parallel execution, retry-then-succeed, rollback, re-plan
scoping), or defends against inputs that can't occur given this project's
hardcoded, known-acyclic graphs.

Run everything: `mvn test` from the repo root builds both modules and runs
the orchestrator's 5 engine tests. Run any scenario (see [SETUP.md](SETUP.md))
to see the url-shortener tests get written and executed for real as part
of the pipeline.

## Scope of what got restored - and what's still missing

Restoring the suite was deliberately **minimal**, not a full revert to
what existed before it was removed:

- **The URL shortener has tests again** (17 assertions across the three
  template-set variants above), including the one that matters most: proof
  the brownfield bug fix actually works.
- **The orchestrator engine has 5 targeted tests**, not the original 13.
  These 5 were chosen specifically because they're the *only* proof of
  their respective mechanisms anywhere in the project (see the list above)
  - unlike the 8 that weren't restored, which duplicate what a live
  scenario run already shows. `DependencyGraphTest` (cycle detection,
  unknown-dependency rejection) was not restored at all: those defend
  against inputs that can never occur, since every graph in this codebase
  is hardcoded and known-valid at compile time.
- **No dedicated test file for the ambiguous scenario's delta**
  (`lastAccessedAt` on the analytics response) - it reuses the greenfield
  suite, which is valid but doesn't specifically assert the new field.

## Other limitations

- **Agents are deterministic templates, not live model calls.** Every agent
  implements `Stage.execute(ctx)` with fixed logic standing in for what an
  LLM-backed executor would produce for the same normalized input. This was
  a deliberate scope choice (see [SUMMARY.md](SUMMARY.md)) so the
  deliverable is fully offline-reproducible and the orchestration mechanics
  are the thing actually being exercised, not prompt quality. The seam
  where a real model call would go is exactly `Stage.execute()` - it takes
  `ExecutionContext` in and returns `StageResult` out either way.
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

## Trade-offs made deliberately

- **Minimal, not exhaustive, test coverage** - 17 url-shortener assertions
  plus 5 targeted orchestrator engine tests (22 total), rather than the
  fuller 21-test suite (8 url-shortener + 13 orchestrator) this project had
  at one point. The 5 restored engine tests were chosen as the ones that
  are the *sole* proof of their mechanism; see "Scope of what got restored"
  above for the honest accounting of what the other 8 left uncovered (in
  practice, nothing - they duplicated live-scenario evidence).
- **Zero runtime dependencies** (no Spring Boot, no Jackson) in exchange for
  hand-rolled HTTP handlers and a minimal flat-JSON parser
  (`JsonUtil`). This guarantees the prototype builds and runs with just a
  JDK, at the cost of not looking like a typical production Java service.
  Explicitly a prototype-speed choice, not a recommendation for a real
  deployment.
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
