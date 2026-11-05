# Getting started

1. Install JDK 21, Maven 3.9+, Git. Verify: `java -version`, `mvn -v`, `git --version`.
2. `./gradlew build` - compiles and runs unit, integration and end-to-end tests (no LLM key needed).
3. `./gradlew bootRun`, then `./scripts/smoke.sh` (approves execution automatically and prints the verdict).
4. Inspect `workspaces/<id>/` for the generated project and `examples/` for sample artifacts.
5. Real LLM: copy `.env.example`, set `LLM_PROVIDER=openai` and `OPENAI_API_KEY`, export, run.

Windows, WSL2, IntelliJ and troubleshooting details are in the README.
