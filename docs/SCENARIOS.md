# Scenarios

All output below is real, captured from actual runs against this repo (see
[SETUP.md](SETUP.md) to reproduce). Each scenario is deliberately built to
exercise different orchestration capabilities so together they cover
everything in the "Workflow Orchestration" requirement, rather than each one
re-demonstrating the same happy path.

| Scenario | Demonstrates |
|---|---|
| Greenfield | Full six-stage pipeline, parallel testing/documentation with synchronization, release approval checkpoint |
| Brownfield | Codebase reasoning, bounded retry with backoff, a bug fix proven by a regression test, dynamic re-planning |
| Ambiguous | Content-dependent human clarification checkpoint, controlled autonomy, safe-stop on denial |

## 1. Greenfield - build from scratch

**Requirement:** *"Build a URL shortener service with an API to create
short links, redirect users to the original URL, and expose basic click
analytics. It needs to be reliable enough for production use: validate
input, rate-limit abusive callers, and support link expiration."*

Unambiguous, so `RequirementsAgent` normalizes it directly - no
clarification checkpoint needed. `ArchitectureAgent` records the component
decision. `ImplementationAgent` writes all 14 source files. Then
`testing` and `documentation` **start within 2ms of each other** (both only
depend on implementation) and run concurrently while `TestingAgent` writes
the test files and runs `mvn test`:

```
STAGE_START  implementation   ...
STAGE_SUCCESS implementation  wrote 14 source file(s): [...]
STAGE_START  testing          Write/refresh tests for 'greenfield-test' and run the build (attempt 1)
STAGE_START  documentation    Regenerate API docs for template set 'greenfield' (attempt 1)
STAGE_SUCCESS documentation   wrote README.md
STAGE_SUCCESS testing         mvn test passed              <- ~6.4s later, real mvn test
APPROVAL_REQUESTED release-readiness  human approval requested before executing '...'
APPROVAL_GRANTED   release-readiness  approved by auto-approve-bot
STAGE_SUCCESS release-readiness  release readiness: GO (mvn test passed)
```

Documentation finishes almost instantly while testing is still mid-build -
concrete proof the two are running in parallel, not just declared to.
`release-readiness` (the one stage every scenario marks
`requiresApproval()`) doesn't start until both finish. Final metrics: 6/6
stages succeeded, 100% success rate, 0 retries, 0 rollbacks, 1 approval
granted.

## 2. Brownfield - hotfix, then scope grows mid-flight

**Requirement:** *"Enhance the existing URL shortener: we've noticed
analytics look wrong on expired links - investigate and fix. Ship that
first, it's affecting reporting today."*

### Pass 1: the hotfix

`ImplementationAgent` diagnoses the bug: the original service recorded a
click *before* checking whether the link had expired, so expired links kept
incrementing their click counter even though every caller got a 404. It
patches `UrlShortenerService.resolve()` to check expiry first. One source
file touched, not a rewrite - and `TestingAgent` adds one new test,
`expiredLinkClickIsNotCounted`, that fails against the pre-fix baseline and
passes against the patch, so the fix is proven, not just claimed (see
[TESTING.md](TESTING.md) for the full test set).

`TestingAgent` is also configured (deliberately, for this demo) to report
one simulated transient infra failure on its first attempt, so the bounded
retry mechanism is observable rather than theoretical:

```
STAGE_START  testing   Write/refresh tests for 'brownfield-bugfix-only-test' and run the build (attempt 1)
STAGE_RETRY  testing   test runner cold-start timeout acquiring an ephemeral port (simulated transient infra failure) -- retrying in 200ms
STAGE_START  testing   Write/refresh tests for 'brownfield-bugfix-only-test' and run the build (attempt 2)
STAGE_SUCCESS testing  mvn test passed
```

Attempt 2 runs a real `mvn test`, including the new regression test. Pass 1
ships: 6/6 stages green, 1 retry triggered, 1 approval granted.

### Pass 2: re-plan after scope changes

Immediately after the hotfix ships, product asks for custom vanity alias
support too. Rather than starting a new run, `BrownfieldScenario` calls:

```java
orchestrator.replan(ctx, "implementation", "add custom vanity alias support on top of the shipped hotfix");
```

```
REPLAN       orchestrator     re-planning from 'implementation': add custom vanity alias support on top of the shipped hotfix
STAGE_START  implementation   Generate/modify the url-shortener source for template set 'brownfield' (attempt 1)
STAGE_SUCCESS implementation  wrote 2 source file(s): [CreateShortUrlHandler.java, UrlShortenerService.java]
STAGE_START  testing          Write/refresh tests for 'brownfield-test' and run the build (attempt 1)
STAGE_START  documentation    Regenerate API docs for template set 'brownfield' (attempt 1)
...
APPROVAL_REQUESTED release-readiness  ...
STAGE_SUCCESS release-readiness  release readiness: GO (mvn test passed)
```

`requirements` and `architecture` are **not** re-run - `replan()` only
recomputes `DependencyGraph.transitiveDependents("implementation")`, i.e.
exactly `{implementation, testing, documentation, release-readiness}`. The
test suite grows again too: `brownfield-test` adds two more tests for the
custom-alias feature on top of the bugfix regression test. The audit log
and decision lineage are continuous across both passes (one `AuditLogger`,
one `ExecutionContext`), and `release-readiness` asks for approval
**again** - shipping the expanded scope is a distinct high-impact action
from the hotfix, so re-approval is correct, not redundant. Final combined
metrics across both passes: 1 retry, 0 rollbacks, 2 approvals granted, 100%
success rate.

## 3. Ambiguous - requirement clarification and controlled autonomy

**Requirement:** *"Add analytics to the URL shortener."*

`RequirementsAgent`'s ambiguity heuristic flags this (a vague trigger phrase
- "add analytics" - with no clarifying detail like "referrer" or "last
access"). Rather than guessing, it proposes a specific interpretation and
pauses on a real approval checkpoint:

```
[auto-approve] requirements: Requirement 'Add analytics to the URL shortener.' does not
specify which analytics signal to expose. Proposed interpretation: track per-link click
count plus last-accessed timestamp (no referrer breakdown, to keep scope minimal).
Approve this interpretation? -> APPROVED (non-interactive mode)
STAGE_SUCCESS requirements  requirement normalized (ambiguityDetected=true)
```

Approved, so `ImplementationAgent` adds `lastAccessedAt` to
`AnalyticsHandler`'s response. `TestingAgent` reuses the `greenfield-test`
suite (the delta doesn't touch `shorten()`'s signature, so it's still
valid) rather than a dedicated test file - see [TESTING.md](TESTING.md) for
what that leaves unasserted. The pipeline completes normally: 6/6 stages, 2
approvals granted (clarification + release), 100% success.

### The other branch: denying the clarification

Run with `-Dexec.args=--auto-deny` instead:

```
[auto-deny] requirements: ... Approve this interpretation? -> DENIED (non-interactive demo mode)
STAGE_FAILED requirements  clarification denied - requirement remains ambiguous
ROLLBACK     requirements  rolled back partial side effects
SAFE_STOP    requirements  halting this branch of the pipeline; independent branches continue
BLOCKED      architecture  safe-stop: upstream dependency did not complete successfully
BLOCKED      implementation  safe-stop: upstream dependency did not complete successfully
BLOCKED      testing         safe-stop: upstream dependency did not complete successfully
BLOCKED      documentation   safe-stop: upstream dependency did not complete successfully
BLOCKED      release-readiness  safe-stop: upstream dependency did not complete successfully
```

Final metrics: 0% success rate, 1 approval requested and denied, 5 stages
`BLOCKED`. This is the intended outcome, not a failure of the system - it's
governance working: the pipeline refuses to guess at an ambiguous
requirement and proceed anyway. `AmbiguousScenario.main()` exits non-zero
in this case and prints a note explaining that's expected.

## Why every run re-derives the baseline first

Every non-greenfield `ImplementationAgent`/`TestingAgent` call re-writes the
full greenfield baseline *before* applying its own delta, rather than only
doing that when files are missing. That wasn't the original design - it's
the fix for a real bug found while building this: running brownfield then
ambiguous back-to-back left `UrlShortenerService.java` in brownfield's
3-argument `shorten()` state, and ambiguous's test file (written against
the 2-argument greenfield signature) failed to compile. The three scenarios
are alternate continuations from the same shipped baseline, not a linear
chain, so each one now always re-derives from that baseline instead of
silently depending on whatever a previous, unrelated run happened to leave
on disk - confirmed by re-running brownfield immediately after ambiguous
and getting the same clean result. See
[SUMMARY.md](SUMMARY.md#things-that-broke-while-building-this) for the
other issues this same kind of end-to-end testing caught.
