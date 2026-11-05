#!/usr/bin/env bash
# Starts the app with the deterministic fake LLM. Requires JDK 21 (JAVA_HOME) and, for builds of generated code, Maven.
set -euo pipefail
cd "$(dirname "$0")/.."
LLM_PROVIDER="${LLM_PROVIDER:-fake}" ./gradlew bootRun
