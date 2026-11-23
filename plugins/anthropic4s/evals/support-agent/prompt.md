---
description: An agent with typed tools, an approval gate and ScriptedModel tests
tags: [agents, tools, testing]
max_turns: 30
timeout_seconds: 600
allowed_tools: [Read, Glob, Grep, Skill]
---

Using anthropic4s, build a customer-support agent in Scala. It answers questions about orders with two tools:

- `lookup_order(orderId)` returns the order's status and total, from a `trait Orders`
- `refund_order(orderId, reason)` issues a refund through a `trait Refunds`. It moves money, so it must never run
  unless a `confirm` callback approves the call.

Write exactly these files:

- `src/main/scala/support/SupportAgent.scala`: the tools and the agent, with a method `ask(question: String): IO[String]`
- `src/test/scala/support/SupportAgentSuite.scala`: tests that need no network or API key, covering a lookup and a refund that the callback declines

Don't run sbt.
