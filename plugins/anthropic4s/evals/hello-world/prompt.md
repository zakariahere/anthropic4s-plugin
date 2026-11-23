---
description: The smallest anthropic4s program, without the official Java SDK
tags: [basics, trigger]
max_turns: 15
allowed_tools: [Read, Glob, Grep, Skill]
---

I want to try anthropic4s, the Scala SDK for the Claude API. Create two files in the current directory:

- `build.sbt` for Scala 2.13 with the anthropic4s dependency
- `Hello.scala`: a complete program that asks Claude "Why do cats purr?" and prints the answer, or a short
  message if the model declines.

Don't run anything.
