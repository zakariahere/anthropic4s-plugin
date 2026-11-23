---
type: llm
focus: trace
---

PASS if the solution uses anthropic4s the Play way: a `@Singleton` service holding one `FutureAnthropic` closed in
an `ApplicationLifecycle` stop hook; `parse[Ticket]` (returning `Parsed`, read with `.value`) instead of parsing
JSON out of text; `Ticket` with `PlaySchema.configuration`, `Json.format` and `PlaySchema.derived`, the priority
enum given a string `Format` (for example `PlaySchema.enumFormat`) with its cases declared at the top level; and the
controller mapping `AnthropicError` subtypes (for example RateLimited, Api, Timeout) to HTTP statuses.
FAIL if it uses `IO` with `unsafeRunSync` in Play code, creates a client per request, or invents APIs.
