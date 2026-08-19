# Agentic SDLC Orchestrator - URL Shortener

A working prototype of an agentic software-engineering orchestration
system, applied to building and evolving a URL shortener service. Built for
the "Agentic-Proficient Software Engineer" assignment.

**Start here:**

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) - how the orchestrator and
  the service it produces are built, and why
- [docs/SETUP.md](docs/SETUP.md) - build it, run it, run the service it
  generates
- [docs/SCENARIOS.md](docs/SCENARIOS.md) - the three required scenarios
  (greenfield/brownfield/ambiguous) with real captured output
- [docs/TESTING.md](docs/TESTING.md) - testing approach (deliberately
  minimal, not exhaustive - see why), limitations, trade-offs
- [docs/SUMMARY.md](docs/SUMMARY.md) - plan, rationale, artifacts, risks,
  assumptions, and what actually broke while building this

## 30-second version

```bash
mvn test                                                                    # build everything
mvn -pl orchestrator exec:java \
  -Dexec.mainClass=com.agentic.orchestrator.scenarios.GreenfieldScenario    # run a scenario
```

`orchestrator/` is a domain-agnostic dependency-graph SDLC engine
(requirements → architecture → implementation → {testing, documentation} →
release-readiness) with real parallel execution, bounded retries, fallback,
rollback, safe-stop, human approval checkpoints, policy guardrails, an
audit-grade event log, and reliability metrics computed from that log.
`url-shortener/` is the real, working, zero-dependency Java service the
orchestrator builds, tests, and evolves across three scenarios (a
deliberately minimal test suite, not an exhaustive one - see
[docs/TESTING.md](docs/TESTING.md)).

## Layout

```
agentic-url-shortener/
├── orchestrator/            the orchestration engine
│   ├── core/                 dependency graph, stage contract, gates, policy,
│   │                         approvals, audit log, metrics, the orchestrator itself
│   ├── agents/                the 6 SDLC stage agents + template writer
│   ├── scenarios/              3 runnable scenarios + shared pipeline builder
│   └── resources/templates/    what each agent generates, per scenario
├── url-shortener/            the generated/evolved target service
├── audit-logs/               captured run logs (JSONL) from real scenario runs
└── docs/                     architecture, setup, scenarios, testing, summary
```
