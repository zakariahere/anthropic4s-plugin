---
description: A decision model routes tickets, with Claude as the fallback when it is unsure
tags: [decide, classification, routing]
max_turns: 25
allowed_tools: [Read, Glob, Grep, Skill]
---

We run Kev-4B on llama-server (System One protocol) next to our Scala 2.13 service. Using anthropic4s 1.1.0, write
a support-ticket router: the local decision model picks the team (billing, shipping or technical) and says how
urgent the ticket is, in one request. When it is not sure of the team, ask Claude Haiku instead. If the decision
model is down, the ticket goes to a human queue rather than failing.

Write exactly these files:

- `src/main/scala/router/TicketRouter.scala`: the router, with a method `route(ticket: String): IO[Routed]`
- `build.sbt`: the dependencies it needs

The endpoint must come from the environment, not from code. Don't run sbt.
