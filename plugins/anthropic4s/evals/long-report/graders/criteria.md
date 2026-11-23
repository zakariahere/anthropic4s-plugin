---
type: llm
focus: { source: file, path: Report.scala }
---

PASS if the report is produced by streaming (`messages.stream(request)` folded with `finalMessage`, or
`textDeltas` compiled to a string, with `import anthropic4s.syntax._`) and maxTokens is large enough for about 50k
tokens. A non-streaming `messages.create` with such a maxTokens would fail with `StreamingRequired`.
FAIL if it calls `create` for the long report, or invents APIs.
