---
description: Play integration with FutureAnthropic and structured output through Play JSON
tags: [play, structured-outputs]
max_turns: 25
allowed_tools: [Read, Glob, Grep, Skill]
---

My Play 3 application (Scala 2.13, Guice) should turn a free-text complaint into a ticket using Claude, through
anthropic4s. A ticket has a one-line title, a priority (Low, Normal or High) and an optional assignee. Write:

- `app/services/Claude.scala`: the service that talks to Claude
- `app/models/Ticket.scala`: the ticket type, with a Play JSON `Format`
- `app/controllers/TicketController.scala`: `POST` action `create` that reads the complaint text from the body and
  returns the ticket as JSON, with sensible HTTP errors when Claude is unavailable

Don't run anything.
