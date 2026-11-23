---
type: llm
focus: { source: file, path: Hello.scala }
---

PASS if Hello.scala is plausible anthropic4s code: `import anthropic4s._`, a client opened with
`Anthropic4s.fromEnv[IO]` (or `resource`) and used inside `.use`, a `MessageRequest(Model.<constant>, <maxTokens>,
MessageParam.user(...))`, `client.messages.create(...)`, and a check of `isRefusal` or the stop reason before
printing `reply.text`. maxTokens must be at most about 21000 because the call does not stream.
FAIL if it uses the official Java SDK's builders, invents methods, hard-codes an API key, or ignores refusals.
