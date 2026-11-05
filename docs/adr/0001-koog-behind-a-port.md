# ADR 0001: Koog behind a typed `StructuredLlm` port

**Status:** accepted

Koog 1.3.0 (`ai.koog:koog-agents`) executes each structured call as a one-node graph agent (`strategy` + `requestLLMStructured<T>()`). Koog's `strategy` needs reified types, so the port exposes one typed method per artifact content (requirements, architecture, implementation, tests, failure analysis). Domain and orchestration never import Koog; providers (fake, OpenAI, Ollama) are selected in `LlmFactory`.

Consequence: swapping providers or frameworks touches only `infrastructure/llm`. Tests use `TaskApiFakeLlm`/`ScriptedLlm`, plus a scripted `PromptExecutor` to exercise the real Koog path offline.
