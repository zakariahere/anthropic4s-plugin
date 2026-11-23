---
type: llm
---

The code has these planted problems:
1. A new client per `ask` call (the client should be created once and shared).
2. `unsafeRunSync` inside application code (stay in IO, or use FutureAnthropic for synchronous or Future callers).
3. `temperature` sent through `withExtra`, which the API rejects.
4. `Model.of("claude-opus-5-5")` where `Model.ClaudeOpus5_5` exists (minor).
5. Non-streaming `create` with maxTokens 64000, which raises `StreamingRequired` (about 21k is the limit at the default timeout).
6. A hand-rolled, single-step tool loop (only the first tool call, no further turns) instead of `toolRunner`.
7. A destructive `delete_file` tool with no approval gate (`RunnerConfig.withBeforeTool`) and no path restriction.

PASS if the review identifies at least 5 of the 7, including 5 and 6, and gives fixes that use real anthropic4s APIs.
FAIL otherwise, or if it recommends APIs that anthropic4s does not have.
