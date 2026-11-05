# AGENTS.md - working safely in this repository

1. Use JDK 21: `export JAVA_HOME=<jdk21>`; build with `./gradlew build` (runs all tests, including an end-to-end test that needs `mvn` and Maven Central and is skipped without `mvn`).
2. Read `.github/copilot-instructions.md` and `docs/architecture.md` first.
3. Keep changes small and tested. Do not weaken validators, path checks, allowlists, timeouts or the approval gate.
4. Do not add features that run LLM-supplied commands, push code, open PRs or access paths outside the workspace.
5. Never commit secrets, `.env`, `data/` or `workspaces/`.
6. Koog code lives only in `infrastructure/llm`; verify any Koog API against the 1.3.0 sources before use.
7. Be truthful in docs and reports: a QA report may claim PASSED only from real test execution.
