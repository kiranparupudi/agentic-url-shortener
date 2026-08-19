# Setup

## Prerequisites

- JDK 17+ (built/tested on JDK 26)
- Maven 3.9+
- No network access required after the first `mvn` run populates
  `~/.m2/repository` (the whole prototype - orchestrator engine and
  url-shortener service - has zero external runtime dependencies; only
  JUnit 5 and the exec-maven-plugin are needed, and only at build time)

## Build everything

```bash
mvn test
```

This builds both modules. The orchestrator module has no test sources of
its own (see [TESTING.md](TESTING.md)); url-shortener's test files don't
exist yet at this point either - they're written by `TestingAgent` the
first time a scenario runs (see below), not checked into the repo
statically, so running `mvn test` right after a fresh clone with no
scenario run yet will find nothing to execute.

## Run a scenario

Each scenario is a runnable `main()` under
`orchestrator/src/main/java/com/agentic/orchestrator/scenarios/`, launched
via the exec-maven-plugin from the **repository root**:

```bash
# Greenfield: build the service from a clear requirement, full happy path
mvn -pl orchestrator exec:java -Dexec.mainClass=com.agentic.orchestrator.scenarios.GreenfieldScenario

# Brownfield: bug fix + retry demo, then a re-plan when scope grows
mvn -pl orchestrator exec:java -Dexec.mainClass=com.agentic.orchestrator.scenarios.BrownfieldScenario

# Ambiguous: vague requirement triggers a clarification checkpoint
mvn -pl orchestrator exec:java -Dexec.mainClass=com.agentic.orchestrator.scenarios.AmbiguousScenario
```

By default every human approval checkpoint (release sign-off, and in the
ambiguous scenario, the clarification itself) is a **real, blocking
interactive prompt on stdin** - the run genuinely pauses and waits for you
to type `y` or `n`. This was a deliberate choice over auto-approving
everything: it's the only way to actually see the governance/safe-stop path
fire instead of just reading about it.

For scripted or unattended runs, append `-Dexec.args=--auto-approve` (every
checkpoint is still logged as a distinct `APPROVAL_REQUESTED` /
`APPROVAL_GRANTED` event, just answered by a bot instead of a human) or, for
the ambiguous scenario only, `-Dexec.args=--auto-deny` to deterministically
exercise the safe-stop path (denies the clarification, watch every
downstream stage get marked `BLOCKED`).

```bash
mvn -pl orchestrator exec:java -Dexec.mainClass=com.agentic.orchestrator.scenarios.GreenfieldScenario -Dexec.args=--auto-approve
```

If `mvn exec:java` reports "No plugin found for prefix 'exec'" the first
time (only happens offline before the plugin is cached), run once with
network access, or use the fully-qualified goal:

```bash
mvn -pl orchestrator org.codehaus.mojo:exec-maven-plugin:3.2.0:java -Dexec.mainClass=com.agentic.orchestrator.scenarios.GreenfieldScenario -Dexec.args=--auto-approve
```

**Recommended order for a first look:** greenfield, then brownfield, then
ambiguous. They're independent (each re-derives its own baseline - see
[ARCHITECTURE.md](ARCHITECTURE.md)) and can run in any order, but this order
matches the narrative in [SCENARIOS.md](SCENARIOS.md).

## Using real Claude AI (optional)

By default every agent is a deterministic template - see
[TESTING.md](TESTING.md#other-limitations) for why. `RequirementsAgent` can
optionally call the real Claude API instead of its keyword heuristic to
judge whether a requirement is ambiguous:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
mvn -pl orchestrator exec:java -Dexec.mainClass=com.agentic.orchestrator.scenarios.AmbiguousScenario -Dexec.args="--auto-approve --use-claude"
```

The ambiguous scenario is where this actually changes anything, since it's
the one requirement that's genuinely ambiguous - Claude judges it the same
way the heuristic does (needs clarification) but writes its own
interpretation instead of the fixed keyword-matched one.

- `ANTHROPIC_MODEL` optionally overrides the model (defaults to
  `claude-haiku-4-5-20251001` - this is a small classification task, not
  worth a bigger model).
- Without `ANTHROPIC_API_KEY` set, `--use-claude` prints a warning and
  falls back to the deterministic heuristic rather than failing the run.
- If the Claude call itself fails for any reason (network, invalid key,
  unexpected response format), `RequirementsAgent` catches it, records why
  in the decision lineage, and falls back to the heuristic for that run -
  a flaky API call never takes down the pipeline.
- This makes that run non-deterministic and dependent on network access,
  unlike every other default-mode run in this project.

## Run the generated service

After any scenario has run, `url-shortener` is a real, runnable service:

```bash
cd url-shortener
java -cp target/classes com.agentic.urlshortener.App
```

```bash
curl -X POST localhost:8080/api/shorten -H "Content-Type: application/json" \
  -d '{"longUrl":"https://example.com/some/long/path","ttlSeconds":3600}'
# {"code":"...","shortUrl":"http://localhost:8080/...","longUrl":"...","expiresAt":"..."}

curl -i localhost:8080/<code>          # 302 redirect
curl localhost:8080/api/analytics/<code>
curl localhost:8080/health
```

`PORT` and `PUBLIC_BASE_URL` environment variables override the defaults
(`8080` / `http://localhost:8080`).

## Inspect a run afterward

- **Audit trail**: `audit-logs/run-<scenario>-<id>.jsonl` - one JSON object
  per event, append-only.
- **Decision lineage & metrics**: printed at the end of every scenario run
  (`RunReport.renderSummary`).
- **Generated artifact**: `url-shortener/README.md` is regenerated by
  `DocumentationAgent` on every run and always reflects whichever template
  set last ran.
