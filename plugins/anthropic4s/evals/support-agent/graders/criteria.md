---
type: llm
focus: { source: file, path: src/main/scala/support/SupportAgent.scala }
---

PASS if the agent is idiomatic anthropic4s: tools built with `Tool("snake_name", "description") { (in: CaseClass) => IO[...] }`
from argument case classes (ideally with `@description` on fields), run through `messages.toolRunner(tools, config)`;
the agent takes `Messages[IO]` (not a client it builds itself) so tests can pass a ScriptedModel; a
`RunnerConfig.withBeforeTool` gate returns `ToolDecision.Deny` for `refund_order` when `confirm` says no; and
`result.stop` is matched rather than reading `result.text` blindly.
FAIL if it writes its own tool loop, invents APIs that anthropic4s does not have, lets refunds run ungated, or
builds a client per call.
