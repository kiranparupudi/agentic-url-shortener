# Final Engineering Summary

## Plan and rationale

The assignment's core differentiator is the orchestration layer, not the
URL shortener itself - so the plan was to build a real, general-purpose
dependency-graph orchestration engine first (`orchestrator/core/`), prove
its mechanics with fast synthetic tests, and only then point it at a
non-trivial target codebase (`url-shortener/`) to demonstrate it isn't a
toy. (Both the engine tests and the url-shortener test suite were later
removed, found to contradict the assignment's own requirements, and fully
restored - see
["A later simplification"](#a-later-simplification-and-why-it-was-partly-reversed)
below for that history stated plainly.) The URL shortener is real, working,
tested code - but its
purpose here is to be a believable engineering artifact for the
orchestrator to reason about, generate, and evolve across three scenarios
that each exercise a different orchestration capability rather than
repeating the same happy path three times.

Key design decisions and why:

- **Explicit DAG, not a linear pipeline** - `DependencyGraph` +
  `computeLevels()` (Kahn's algorithm) so parallel stages (testing,
  documentation) are a structural property of the graph, not a special
  case bolted on.
- **Governance as first-class, separate concerns** - gates (business
  pre/postconditions), policy (security/compliance, centrally owned), and
  approval checkpoints (human sign-off) are three distinct mechanisms
  layered onto every stage, not conflated into one "is this okay?" check.
- **One audit event stream is the single source of truth** - reliability
  metrics are *computed from* the audit log, never tracked separately, so
  they can't silently drift from what actually happened.
- **Deterministic template agents by default** - see
  [TESTING.md](TESTING.md#trade-offs-made-deliberately) for the reasoning;
  in short, reproducibility and keeping evaluation weight on the
  orchestration mechanics rather than prompt quality. `RequirementsAgent`
  is the one agent with a real Claude integration behind it, opt-in via
  `--use-claude` (see `orchestrator/ai/` and
  [SETUP.md](SETUP.md#using-real-claude-ai-optional)) - everything else
  stays template-only.
- **Zero-runtime-dependency target service** - guarantees the whole
  prototype builds and runs from a bare JDK, no network access needed after
  the first `mvn` run.

## Artifacts produced

- `orchestrator/` - the engine: `core/` (graph, stage contract, gates,
  policy, approvals, audit log, metrics, orchestrator), `agents/` (6 SDLC
  stage agents + template writer), `ai/` (`LlmClient`, `ClaudeClient`,
  `LlmClientFactory` - the real Claude integration behind
  `RequirementsAgent`), `scenarios/` (3 runnable scenarios + shared
  pipeline builder)
- `orchestrator/src/main/resources/templates/` - the versioned "what each
  agent generates" content: greenfield baseline, brownfield delta (in two
  increments, for the re-plan demo), ambiguous delta
- `url-shortener/` - the generated/evolved service: 14 source files, a
  small test suite (written per template set by `TestingAgent`, not
  checked in statically), README regenerated per run
- `audit-logs/` - real captured run logs (JSONL) from the scenarios
  described in [SCENARIOS.md](SCENARIOS.md)
- `docs/` - this document plus ARCHITECTURE, SETUP, SCENARIOS, TESTING

## Risks, trade-offs, and validation

Covered in depth in [TESTING.md](TESTING.md#limitations) and
[TESTING.md](TESTING.md#trade-offs-made-deliberately) - summarized: agents
are deterministic (not live LLM calls), re-plan triggering is manual (not
auto-detected from artifact diffs), persistence is in-memory only, rollback
covers file writes but not build artifacts, and the policy ruleset is
illustrative rather than production-complete. Each of these is a scope
boundary chosen deliberately for a 2-3 day prototype, not an oversight, and
each has a documented seam for where the real version would plug in.

## Assumptions

- "Controlled autonomy" means agents execute multi-step work but every
  high-impact action (shipping a release; accepting an ambiguous
  requirement's interpretation) requires an explicit human decision the
  system actually waits on, not a logged-but-ignored notification.
- The three required scenarios (greenfield/brownfield/ambiguous) are better
  demonstrated as three *different capabilities* of one consistent
  governance model than as three isolated demos - see the comparison table
  at the top of [SCENARIOS.md](SCENARIOS.md).
- A prototype evaluated primarily on orchestration quality should not
  require an API key or network access to run end-to-end; every agent had
  to work fully offline.

## Things that broke while building this

Worth stating plainly, since it's real validation evidence: running the
actual three scenarios back-to-back (not just reading the code) surfaced
three genuine bugs, all fixed and re-verified:

1. **A policy rule checked at the wrong stage.** `tests-required-for-new-endpoints`
   was originally evaluated at `implementation`'s exit gate, before
   `testing` had ever run - so it failed on every single run,
   unconditionally. Fixed by scoping it to `release-readiness`, where
   `testsExistForImplementation` is actually meaningful. Still in place
   today.
2. **Cross-scenario state leakage.** Running brownfield then ambiguous
   left `UrlShortenerService.java` in brownfield's 3-argument `shorten()`
   state; ambiguous's test file (written against the 2-argument baseline
   signature) failed to compile. Fixed by having every non-greenfield run
   always re-derive the full baseline first instead of only bootstrapping
   when files were missing - see
   [SCENARIOS.md](SCENARIOS.md#why-every-run-re-derives-the-baseline-first).
   A related instance of the same class of bug: a test file added by one
   scenario (`ShortUrlRecordTest.java`, ambiguous-only) was left behind and
   broke a *different* scenario's build later. Fixed by having `TestingAgent`
   delete any test file that isn't part of the current scenario's expected
   set before running the build - re-verified in both directions
   (ambiguous → brownfield and brownfield → ambiguous) after
   `ShortUrlRecordTest.java` was reintroduced.
3. **A metrics gap.** The ambiguity clarification checkpoint is
   content-dependent, so it can't use the orchestrator's static
   `requiresApproval()` flag - `RequirementsAgent` calls the approval
   gateway itself, mid-`execute()`. That path wasn't logging
   `APPROVAL_REQUESTED`/`APPROVAL_DENIED` events, so `MetricsCollector`
   silently under-counted approvals for that one stage. Fixed by handing
   `RequirementsAgent` the same `AuditLogger` the orchestrator uses.

All three were caught by actually running the pipeline end-to-end and
reading the output, not by a test - which is the same argument this
assignment makes for validation and risk control in general: a system that
looks correct on paper still has to be run.

## A later simplification, and why it was partly reversed

After the working prototype above was validated, the test framework (JUnit,
all unit tests - both the orchestrator's 13 engine tests and the
url-shortener's 8 service tests - and the generated test-file templates)
was removed on explicit request to shrink the codebase.

That removal directly contradicted four things the assignment's own
requirements document states: unit/integration tests are named under
"Engineering Output Generation", "testable" is a named evaluation
criterion, "Testing approach, limitations, and trade-offs" is a required
deliverable section, and "testing" is one of the six explicit SDLC
lifecycle stages the orchestration layer is supposed to coordinate.

Once that was pointed out, restoration happened in three passes rather
than all at once: first a minimal url-shortener suite (proving the
brownfield bug fix via `expiredLinkClickIsNotCounted`), then the 5
orchestrator engine tests that are each the *sole* proof of their
mechanism (fallback, retry exhaustion, entry-gate failure, the static
`requiresApproval()` denial path, and independent-branch continuation -
none of which any live scenario run can demonstrate), and finally the
remaining 8 engine tests plus a dedicated test for the ambiguous
scenario's `lastAccessedAt` field, closing out both gaps that the
in-between state had explicitly flagged as open. The end state is a full
test suite again - every template-set variant has its own tests, and the
orchestrator engine has all 13 of its original tests back. See
[TESTING.md](TESTING.md#testing-approach) for the current breakdown.

## Limitations

See [TESTING.md](TESTING.md#limitations) for the full list.
