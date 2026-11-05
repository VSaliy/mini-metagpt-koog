# Copilot instructions for mini-metagpt-koog

See also `AGENTS.md`.

## Architecture principles
- Packages under `io.github.vsaliy.minimetagpt`: `domain` (pure contracts, no Spring/Koog), `agents` (role logic; depends on the `StructuredLlm` port), `orchestration` (workflow engine/state), `infrastructure` (Koog, JDBC, process/tools, config), `api` (REST).
- Agents exchange validated typed artifacts, never free text. Every artifact is validated (`ArtifactValidators`) before it reaches the next stage.
- Koog is confined to `infrastructure/llm`. Domain and orchestration must not import `ai.koog.*`. New providers are added in `LlmFactory` only.
- Workflow transitions are explicit (`WorkflowStatus.canTransitionTo`); terminal workflows are immutable.

## Conventions
- Kotlin: immutable data classes, no `!!` in production code, constructor injection, suspend functions for I/O; always rethrow `CancellationException`.
- Generated projects are Java 21 / Spring Boot / Maven.

## Testing
- Tests must run offline with no paid LLM: use `TaskApiFakeLlm` or `ScriptedLlm`.
- Add unit tests for every validator, transition, retry limit and tool argument check; integration tests for REST/persistence.
- Run with JDK 21: `./gradlew build`.

## Security
- Treat LLM output and generated code as untrusted. Never run LLM-provided shell commands; only allowlisted commands with validated args (`MavenTool`, `GitTool`).
- All file access goes through `Workspace` (no traversal, no symlinks, no `.git` writes). No git push, no PRs, no remotes.
- Never log or write API keys. Execution of generated code requires approval by default.

## Definition of Done
Builds, all tests pass, docs updated, no secrets, no placeholder/mock-only features reported as complete.

## Do not
- Invent Koog/Spring APIs or artifact coordinates: verify against sources or Maven Central.
- Leave `TODO` stubs that pretend to work; report limitations honestly.
