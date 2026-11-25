---
type: llm
focus: { source: file, path: src/main/scala/router/TicketRouter.scala }
---

PASS if the router uses anthropic4s-decide as documented: typed questions (`Question.choice[Team]` with
`Criterion`s whose descriptions say what each team handles, and `Question.score` for urgency) asked in one request
with `decide.run(state, (q1.as("..."), q2.as("...")).tupled)`; a fallback to Claude (`Model.ClaudeHaiku5_5`
through `Messages[IO]` or the client) gated on `answer.confidence`, not on the raw top probability; the
configuration from `DecideConfig.fromEnv`; one `Decide` shared rather than built per call (taken as a parameter,
or from `Decide.ember`/`Decide.fromClient` once); and `DecideError.Unavailable` (and ideally `Timeout`) recovered
into the human queue.
FAIL if it calls the System One endpoint with a hand-written HTTP request, parses the answer JSON itself, invents
APIs that anthropic4s-decide does not have (such as a `classify` method or string-typed answers), hard-codes the
endpoint, or lets a down decision model crash the route.
