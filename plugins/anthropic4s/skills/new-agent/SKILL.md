---
name: new-agent
description: Scaffold and build a new Claude agent in Scala with anthropic4s, end to end, from an empty folder to a tested sbt project with typed tools, an agent loop, ScriptedModel tests and an optional real run. Use when the user wants to start, create or bootstrap a new agent, bot, assistant or tool-calling program in Scala on the Claude API.
argument-hint: "[what the agent should do]"
---

# New anthropic4s agent

Build the agent the user described in `$ARGUMENTS`. Load the `anthropic4s` skill first: it holds the API rules,
and its references (`tools-and-agents.md`, `testing.md`) hold compiled examples. Do not write anthropic4s code
from memory.

The phases below happen in order. Stop at each **checkpoint**.

## 1. Pin down the agent

Ask only for what you cannot infer, and keep it to one short round:

- **The job**: what one run takes in and what it returns.
- **The tools**: what each one reads or changes. Mark every side effect (money, email, writes, deletes). Each
  one gets an approval gate. A gate can ask a local decision model in milliseconds (did the user really ask for
  this?) instead of a person; see `references/decision-models.md` of the `anthropic4s` skill, and make it fail
  closed.
- **Where it runs**: a command-line program (the default, which is this template) or inside a Play application
  (then follow `references/play.md` of the `anthropic4s` skill and use `FutureAnthropic` instead of `Main`).
- **The model**: `Model.ClaudeOpus5_5` unless the user says otherwise. `Model.ClaudeHaiku5_5` suits cheap,
  high-volume, simple jobs.

## 2. Scaffold

The template is the `template/` folder next to this SKILL.md. It is a complete sbt project that CI compiles and
tests against anthropic4s 1.1.0:

```
template/
  build.sbt                         anthropic4s + testkit, Scala 2.13.18
  project/build.properties          sbt 1.13.1
  src/main/scala/agent/Tools.scala  typed tools (one example: current_time)
  src/main/scala/agent/Agent.scala  system prompt + RunnerConfig + RunStop handling, over Messages[IO]
  src/main/scala/agent/Main.scala   IOApp: one client, one question from the command line
  src/test/scala/agent/AgentSuite.scala  ScriptedModel tests, no network
```

1. Copy it into the target folder. Never overwrite existing files; if the folder is not empty, ask first.
2. Rename `name := "my-agent"` and, if the user wants it, the `agent` package.
3. Run `sbt test` once **before changing anything**, to prove the toolchain works (JDK 17+, sbt). The template's
   3 tests must pass.

**Checkpoint:** if the build fails here, fix the environment before writing any agent code.

## 3. Tools first

For each tool, in `Tools.scala`:

- write an argument case class with `@description` on every field whose format is not obvious (units, ids,
  ISO dates);
- write a result case class (compact JSON beats prose);
- use a `snake_case` name and a description that says what the tool does **and when to use it**, naming side
  effects;
- implement it behind a small trait (`trait Orders { def find(id: String): IO[Option[Order]] }`), so tests can
  use fakes.

Delete the `current_time` example unless the agent needs it.

## 4. The agent

In `Agent.scala`:

- Write a system prompt that states the job, what each tool is for, when to stop, and the output format.
- Add a `withBeforeTool` gate for every side-effecting tool (`Deny` with a reason the model can act on).
- Keep `withMaxIterations` at 10–15 unless the task needs more.
- Keep the class taking `Messages[IO]`, not a client. That is what makes it testable.
- Leave the `RunStop` match exhaustive and give each case a deliberate answer.

## 5. Tests before tokens

In `AgentSuite.scala`, with `ScriptedModel` and `Replies` (`references/testing.md`), cover at least:

- the happy path: tool call(s), then the answer, asserting the tool results that `model.requests` sent back;
- a tool that fails or gets bad input: an error result goes back and the run continues;
- each gate: a denied call never reaches the fake service (track it with a `Ref`);
- a refusal and `MaxIterations`: mapped to their answers.

Run `sbt test` until it passes.

**Checkpoint:** show the user the tool list, the system prompt and the test results.

## 6. A real run (optional, costs money)

Ask before doing this: it needs `ANTHROPIC_API_KEY` in the environment and spends real tokens. Never ask the
user to paste the key into the chat, and never write it into a file. Then run:

```
sbt "run <a realistic question>"
```

If the answer is wrong, change the system prompt or the tool descriptions, not the tests. If a real reply
exposes a case the tests missed, add a scripted test for it.

## 7. Wrap up

- Optional tracing: add `anthropic4s-otel4s` (`references/tracing.md`).
- Run the `anthropic4s-reviewer` agent over the project, and fix what it reports.
- Tell the user how to run it and test it, and what the gates protect.
