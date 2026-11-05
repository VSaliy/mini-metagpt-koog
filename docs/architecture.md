# Architecture

```
api ──> orchestration ──> agents ──> StructuredLlm (port) <── infrastructure/llm (Koog, fake)
              │               │
              │               └──> infrastructure/tools (Workspace, ProcessRunner, GitTool, MavenTool)
              └──> WorkflowRepository (port) <── infrastructure/persistence (JDBC + Flyway)
domain: contracts shared by all (no framework dependencies)
```

## Workflow (SOP)
`WorkflowEngine` runs: requirements -> architecture -> development -> QA test generation -> git snapshot -> approval gate -> build/test loop -> report.
- Each stage output is validated by `ArtifactValidators`; invalid LLM output is retried with the validation feedback up to `max-output-retries`, then the workflow fails with `INVALID_OUTPUT`/`VALIDATION_FAILED`.
- On build/test failure QA analyses the failure; cause `TEST` regenerates tests, otherwise the Developer corrects the code. At most `max-correction-attempts` corrections happen (`RETRIES_EXHAUSTED` afterwards). Diagnostics (output tails, analyses) are preserved in the report and error.
- Status machine: CREATED -> RUNNING <-> WAITING_FOR_APPROVAL -> COMPLETED | FAILED | CANCELLED. Terminal states are immutable; all updates go through a per-workflow lock (`WorkflowStateStore`).
- Cancellation cancels the coroutine, which kills any running build process tree.
- Verdict `PASSED` requires real Surefire results with at least one test and none failing; otherwise `FAILED`, or `NOT_EXECUTED` when execution was disabled/rejected.

## Koog integration
Only `KoogStructuredLlm` uses Koog: a one-node `strategy` calling `requestLLMStructured<T>()` inside an `AIAgent` built with a `PromptExecutor` (`OpenAILLMClient` or `OllamaClient` wrapped in `MultiLLMPromptExecutor`).

## Security model
Allowlisted tools only; `Workspace` rejects traversal, absolute paths, symlinks, `.git` writes; developer output cannot write `src/test`; env scrubbed; timeouts; no push. See ADR 0002 for the sandbox limitation.

## Observability
`StageObserver` sets MDC (`workflowId`, `agentRole`, `stage`), opens a Micrometer/OpenTelemetry span and logs duration, outcome and error category per stage. OTLP export is opt-in.

## Persistence
Tables `workflow_execution`, `artifact`, `agent_execution` (Flyway `V1__init.sql`).
