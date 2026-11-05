# mini-metagpt-koog

A miniature AI software factory inspired by MetaGPT, built on [JetBrains Koog](https://github.com/JetBrains/koog). A natural-language requirement flows through four specialised agents that exchange **validated, typed artifacts**, producing a generated Java 21 / Spring Boot / Maven project that is compiled and tested for real.

```
Request -> Requirements -> Architect -> Developer -> QA (generate tests)
        -> [approval] -> mvn compile/test -> (failure: analysis + correction, bounded) -> Quality report
```

## Versions
Java 21 · Kotlin 2.3.10 · Spring Boot 3.5.16 · Koog `ai.koog:koog-agents:1.3.0` · Gradle 8.14.3 (wrapper) · Flyway · Micrometer Tracing + OpenTelemetry OTLP · JUnit 5 · generated projects: Spring Boot 3.5.16, Maven.

## Build, test, run
Prerequisites: **JDK 21**, and for building generated projects **Maven 3.9+**, **Git** and access to Maven Central.

```bash
export JAVA_HOME=/path/to/jdk-21            # Windows PowerShell: $env:JAVA_HOME="C:\path\to\jdk-21"
./gradlew build                             # Windows: gradlew.bat build   (compiles and runs all tests)
./gradlew bootRun                           # starts on http://localhost:8080 with the deterministic fake LLM
```

Tests need no LLM key. The end-to-end smoke test uses the fake LLM and real Maven; it is skipped if `mvn` is missing.

By default a workflow **pauses in `WAITING_FOR_APPROVAL`** before generated code is executed. Approve it with `POST /api/v1/workflows/{id}/approve` (or `/reject`). Set `EXECUTION_REQUIRE_APPROVAL=false` to skip the gate on a trusted/disposable machine.

### Example requests
```bash
curl -i -X POST localhost:8080/api/v1/workflows -H 'Content-Type: application/json' \
  -d '{"requirement":"Create a Spring Boot REST API for managing tasks with CRUD operations and validation."}'
curl localhost:8080/api/v1/workflows/<id>
curl -X POST localhost:8080/api/v1/workflows/<id>/approve
curl localhost:8080/api/v1/workflows/<id>/artifacts
curl localhost:8080/api/v1/workflows/<id>/report
curl -X POST localhost:8080/api/v1/workflows/<id>/cancel
curl localhost:8080/actuator/health
./scripts/smoke.sh        # create + approve + poll + print verdict
```
Generated projects are written to `./workspaces/<workflow-id>/` (own git repo, never pushed). Sample artifacts: `examples/`.

### REST API
| Method | Path | Notes |
|---|---|---|
| POST | `/api/v1/workflows` | 202 + `Location`; 400 on invalid input |
| GET | `/api/v1/workflows/{id}` | status, stage, error, per-stage execution history |
| GET | `/api/v1/workflows/{id}/artifacts` | typed artifacts with provenance |
| GET | `/api/v1/workflows/{id}/report` | 409 until a report exists |
| POST | `/api/v1/workflows/{id}/cancel` | 409 if already finished |
| POST | `/api/v1/workflows/{id}/approve` , `/reject` | resolves `WAITING_FOR_APPROVAL` |
| GET | `/actuator/health` | |

Statuses: `CREATED, RUNNING, WAITING_FOR_APPROVAL, COMPLETED, FAILED, CANCELLED`. Errors are `{code, message, details, path, timestamp}`.

### LLM providers
`LLM_PROVIDER=fake` (default) · `openai` (`OPENAI_API_KEY`, optional `LLM_MODEL`/`LLM_BASE_URL`) · `ollama` (`LLM_BASE_URL`, `LLM_MODEL`). See `.env.example`.
> The OpenAI and Ollama paths use Koog's real clients but are **not exercised in CI** (no keys/servers); the Koog agent/structured-output path itself is tested with a scripted `PromptExecutor`. Token usage is recorded only when the provider returns it; cost is never estimated.

### Optional services
`docker compose up -d postgres` then `DB_URL=jdbc:postgresql://localhost:5432/minimetagpt DB_USER=minimetagpt DB_PASSWORD=minimetagpt ./gradlew bootRun`. Traces: `docker compose --profile observability up -d otel-collector` and `OTEL_TRACES_EXPORT_ENABLED=true`. (The compose file and the PostgreSQL migration path were not run in the authoring environment; H2 is what the tests cover.)

## Platform notes
- **Linux**: install JDK 21 (e.g. Temurin), Maven, Git; commands above.
- **WSL2**: work inside the Linux filesystem (`~/`), not `/mnt/c`, for speed and symlink support; install JDK 21/Maven in WSL.
- **Windows**: use `gradlew.bat` and PowerShell; Maven and Git must be on `PATH` (or set `MAVEN_COMMAND`).
- **IntelliJ IDEA**: *File > Open* the repo directory (Gradle project), set Project SDK and Gradle JVM to 21, run `MiniMetagptApplication`.

## Troubleshooting
- `Unsupported class file major version` / Kotlin JVM target errors: Gradle is running on a JDK other than 21 - fix `JAVA_HOME`.
- Workflow stuck in `WAITING_FOR_APPROVAL`: approve it or disable the gate.
- Report verdict `NOT_EXECUTED`: execution disabled or rejected; no tests were run.
- Build step fails with network errors: the generated project needs Maven Central.
- Port 8080 busy: `SERVER_PORT=8081`.
- `FAILED/INTERRUPTED`: the app restarted during a workflow; workflows are not resumed.

## Known limitations
- **No real sandbox**: generated code runs as the app user with a scrubbed environment, timeouts and a kill switch, but without CPU/memory/network/filesystem isolation. Hence the approval gate (default on); use a disposable environment. See `docs/adr/0002-untrusted-code-execution.md`.
- Workflows are not resumed after a restart; in-process async execution only.
- Fake LLM only produces the Task CRUD API; other requirements need a real provider.
- OpenAI/Ollama, PostgreSQL and Docker Compose are unverified here (see above).

More: `docs/architecture.md`, `docs/getting-started.md`, `docs/agent-contracts.md`, `docs/adr/`, `AGENTS.md`.
