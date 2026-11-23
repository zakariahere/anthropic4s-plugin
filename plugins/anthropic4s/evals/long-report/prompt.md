---
description: Large outputs must stream, because create() refuses them
tags: [streaming]
max_turns: 15
allowed_tools: [Read, Glob, Grep, Skill]
---

With anthropic4s, write `Report.scala` containing an object `Report` with
`def write(messages: Messages[IO], topic: String): IO[String]`. It asks Claude for a very long, detailed report,
up to about 50,000 output tokens, and returns its full text. Don't run anything.
