# ADR 0002: Generated code is untrusted; approval gate instead of a sandbox

**Status:** accepted

Running `mvn test` on LLM-generated code executes arbitrary code. No container sandbox is bundled in the MVP. Mitigations: per-workflow isolated workspace, no shell, allowlisted Maven goals, scrubbed environment (no API keys), timeouts, bounded output, process-tree kill, and a human approval gate (`minimetagpt.execution.require-approval=true` by default). `minimetagpt.execution.enabled=false` disables execution entirely.

Known limitation: no CPU/memory/network isolation. Run on a disposable machine or add container isolation before exposing the service.
