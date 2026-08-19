# Architecture

## Two things this repo builds

1. **The orchestrator** (`orchestrator/`) - a domain-agnostic agentic SDLC
   engine: a dependency graph of stages, executed with gates, retries,
   fallback, rollback, safe-stop, human approval checkpoints, policy
   guardrails, audit logging and reliability metrics.
2. **The URL shortener** (`url-shortener/`) - the engineering outcome the
   orchestrator produces and evolves. A zero-runtime-dependency Java service
   (`com.sun.net.httpserver`, no Spring/Jackson/etc.) so the whole prototype
   builds and runs offline.

The orchestrator is the actual deliverable for this assignment; the URL
shortener is the proof it works on a real, non-trivial codebase rather than
a toy.

## Orchestration model

```
requirements ──▶ architecture ──▶ implementation ──┬──▶ testing ───────┐
                                                     └──▶ documentation ─┴──▶ release-readiness
```

This is an explicit `DependencyGraph` (`core/DependencyGraph.java`), not a
linear list. `computeLevels()` runs Kahn's algorithm to group stages into
levels: every stage in a level has all its dependencies satisfied by a
strictly earlier level, so stages in the same level execute **concurrently**
on a shared thread pool while levels run in sequence. `testing` and
`documentation` both only depend on `implementation`, so they run in
parallel and `release-readiness` is the synchronization point that waits on
both - this is the "support sequential and parallel paths with
synchronization" requirement made concrete, not simulated.

### Stage contract (`core/Stage.java`)

Every stage declares:

- `dependsOn()` - explicit edges in the graph
- `entryGate()` / `exitGate()` - precondition/postcondition checks, separate
  from the stage's own business logic and from policy
- `retryPolicy()` - bounded retries with exponential backoff
- `fallback()` - an alternate `Stage` tried once if retries are exhausted
- `rollback(ctx)` - best-effort undo of the stage's own side effects
- `requiresApproval()` - static flag for a human checkpoint gating
  execution entirely

`Orchestrator.runStage()` (`core/Orchestrator.java`) is the single place all
of this is enforced, in order: entry gate → approval checkpoint → execute
(with retry loop) → exit gate + policy evaluation → on failure: fallback →
rollback → safe-stop. A stage failing only halts *its own branch* -
`runStageWithHalt()` only marks a stage `BLOCKED` if one of *its own*
dependencies failed, so independent parallel branches keep running. None of
the three scenarios' fixed 6-stage graph can actually demonstrate this
live (`testing`/`documentation` share one dependency rather than depending
on each other, so there's no topology where one sibling fails while an
unrelated one keeps going) - it's covered instead by
`OrchestratorTest.independentBranchKeepsRunningWhenSiblingFails`, one of
the 5 engine tests kept specifically because nothing else proves it (see
[TESTING.md](TESTING.md#testing-approach)).

### Dynamic re-planning

`Orchestrator.replan(ctx, changedStageId, rationale)` computes
`DependencyGraph.transitiveDependents(changedStageId)`, marks that stage and
everything downstream of it back to `PENDING`, and re-executes only that
subgraph - stages outside the affected set (and their artifacts) are left
untouched. Every gate, policy check and approval checkpoint applies again to
the re-run stages exactly as it did the first time; nothing about re-running
bypasses governance. See `BrownfieldScenario` and its pass-2 walkthrough in
[SCENARIOS.md](SCENARIOS.md#pass-2-re-plan-after-scope-changes) for this
driven by an actual scope change, not a synthetic demo.

### Governance primitives

- **Approval checkpoints** (`core/ApprovalGateway.java`): a stage can
  declare `requiresApproval()` (checked by the orchestrator before it
  executes at all), or an agent can call the gateway itself mid-execution
  for a content-dependent decision (`RequirementsAgent` does this for
  ambiguity clarification). Both paths log the same
  `APPROVAL_REQUESTED` / `APPROVAL_GRANTED` / `APPROVAL_DENIED` audit
  events so reliability metrics stay accurate regardless of which path was
  used.
- **Policy guardrails** (`core/PolicyEngine.java`): rules evaluated against
  every stage's exit, independent of the stage's own logic - e.g. "no
  hardcoded secrets in generated source", "tests must exist before
  release", "release requires a green build".
  A `BLOCKING` violation fails the stage even if its own business logic
  reported success.
- **Audit trail** (`core/AuditLogger.java`): every gate check, retry,
  rollback, approval and policy decision is appended as a JSON line to
  `audit-logs/run-<id>.jsonl` and mirrored to the console. This is the
  single source of truth `MetricsCollector` computes from - metrics can't
  drift from what actually happened because they're derived from the same
  event stream, not tracked separately.
- **Decision lineage** (`core/Decision.java` / `ExecutionContext`): agents
  record *why* they made a non-obvious call (an interpretation of an
  ambiguous requirement, a bug diagnosis, an architectural trade-off) as a
  running list threaded through the whole context, independent of the audit
  log's operational events.

## The agents (`orchestrator/agents/`)

Each agent is a `Stage` implementation with deterministic logic standing in
for what an LLM-backed executor would produce for the same normalized
input - the seam is exactly `Stage.execute(ExecutionContext ctx)`, which
takes only the shared context in and returns a typed result out, the same
shape a tool-using model call would have. `RequirementsAgent` actually uses
that seam for real - see `orchestrator/ai/` (`LlmClient`, `ClaudeClient`,
`LlmClientFactory`) and [SETUP.md](SETUP.md#using-real-claude-ai-optional).
The other five are still template-only; swapping a real model in for any of
them means replacing one method body, same pattern.

| Agent | Depends on | What it actually does |
|---|---|---|
| `RequirementsAgent` | - | Flags vague requirements and proposes a concrete interpretation, then pauses on a real approval checkpoint before normalizing if ambiguous. Uses a keyword heuristic by default, or a real Claude call if given an `LlmClient` |
| `ArchitectureAgent` | requirements | Records the component design decision (in-process HTTP server, in-memory store behind an interface, Base62 codes, rate limiter) with rationale |
| `ImplementationAgent` | architecture | Writes real `.java` files into `url-shortener/src/main/java` from a bundled template set, tracking a before/after backup for rollback |
| `TestingAgent` | implementation | Writes the test files for the template set, then actually runs `mvn -pl url-shortener test` - a real build, not a simulated pass/fail (see [TESTING.md](TESTING.md) for what each template set covers) |
| `DocumentationAgent` | implementation | Regenerates `url-shortener/README.md` from the current implementation + architecture decision |
| `ReleaseReadinessAgent` | testing, documentation | Final go/no-go: green build + docs present + no blocking policy violations, gated on human approval |

`TemplateWriter` copies a named template set from
`orchestrator/src/main/resources/templates/<set>/` onto disk and (for
`ImplementationAgent`) records each file's previous content so
`rollback()` can restore it. Every non-`greenfield` template set is applied
as a **delta on top of a freshly re-derived greenfield baseline** - see
[SCENARIOS.md](SCENARIOS.md#why-every-run-re-derives-the-baseline-first) for
why that specific design choice exists (it's the fix for a real bug found
while building this).

## Reliability metrics (`core/MetricsCollector.java`)

Computed purely from the audit event stream: stage success/fail/blocked
counts, success rate, retry count, rollback count, approvals
requested/denied, mean time to recover (time between a stage's first
failure and its eventual success), and end-to-end run latency. Printed as
part of every `RunReport`.
