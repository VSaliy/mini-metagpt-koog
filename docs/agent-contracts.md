# Agent contracts

All artifacts carry `meta`: `id`, `workflowId`, `schemaVersion` ("1.0"), `createdAt`, `provenance` (`producedBy`, input `ArtifactReference`s, attempt, model, token usage if reported) and `validation` (`valid`, `issues`). LLMs only produce the `content` part (kotlinx-serialization types); envelopes are added by the agents.

| Agent | Input | Output | Key validation |
|---|---|---|---|
| Requirements | user request | `RequirementsArtifact` (functional/non-functional requirements, acceptance criteria, assumptions, open questions) | non-empty, unique ids, criteria reference existing requirements |
| Architect | requirements | `ArchitectureArtifact` (components, endpoints, entities, DTOs, persistence, dependencies, decisions) | endpoints/components consistent, valid HTTP methods |
| Developer | requirements + architecture (+ failure analysis on correction) | `ImplementationArtifact` (file manifest with sha256) | safe relative paths, `pom.xml` present, no `src/test`, size limits |
| QA | requirements, architecture, implementation | `QualityReportArtifact` | test paths under `src/test/java`; verdict derived from real build results |

Examples: `examples/sample-requirements.json`, `sample-architecture.json`, `sample-quality-report.json` (captured from a real run with the fake LLM).
