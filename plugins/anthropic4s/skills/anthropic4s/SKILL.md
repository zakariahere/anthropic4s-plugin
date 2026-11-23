---
name: anthropic4s
description: How to call the Claude API from Scala with anthropic4s (io.github.zakariahere %% anthropic4s, Cats Effect + fs2). Use it whenever Scala code calls Claude, builds an agent or tool loop, streams replies, asks for structured output as case classes, or uses anthropic4s from Play with Future, and whenever a build.sbt or code mentions anthropic4s, AnthropicClient, MessageRequest, ToolRunner or FutureAnthropic. anthropic4s 1.0.0 shipped in November 2026, so recall alone will produce APIs it does not have. Read this skill first.
---

# anthropic4s: the Claude API from Scala

anthropic4s is an **unofficial**, pure-Scala SDK for the Claude API (not affiliated with Anthropic), built on
Cats Effect 3 and fs2, for Scala 2.13 and JDK 17+. It is **not** a wrapper around the official Java SDK. Nothing
from `com.anthropic.*` applies: there is no `AnthropicOkHttpClient`, no `MessageCreateParams.builder()` and no
Jackson. Everything is in `import anthropic4s._`.

Docs: https://anthropic4s.zakaria.lu/docs/ · API reference: https://anthropic4s.zakaria.lu/reference/

## Install

```scala
libraryDependencies += "io.github.zakariahere" %% "anthropic4s" % "1.0.0"
// optional, same version: anthropic4s-play-json, anthropic4s-otel4s, anthropic4s-testkit (% Test)
```

## The smallest program

<!-- snip: Basics.scala#hello -->
```scala
def run: IO[Unit] =
  Anthropic4s.fromEnv[IO].use { client => // reads ANTHROPIC_API_KEY; closes its connections afterwards
    val request = MessageRequest(Model.ClaudeOpus5_5, maxTokens = 16000, MessageParam.user("Why do cats purr?"))

    client.messages.create(request).flatMap { reply =>
      // Look at how the reply ended before trusting its content.
      if (reply.isRefusal) IO.println("The model declined.")
      else IO.println(reply.text) *> IO.println(reply.usage.show)
    }
  }
```

Imports for it: `anthropic4s._`, `cats.effect.{IO, IOApp}`, `cats.syntax.all._` (for `.show`).

## Rules that recall gets wrong

1. **One client, as a `Resource`.** `Anthropic4s.fromEnv[IO]` or `Anthropic4s.resource[IO](AnthropicConfig(key))`
   opens it. Share it for the life of the application. Never build one per request. From Play or other `Future`
   code use `FutureAnthropic` and close it in a stop hook (see `references/play.md`).
2. **Make application code depend on `Messages[IO]`**, which is `client.messages`, not on the client. The testkit's
   `ScriptedModel` is a `Messages[IO]`, so the same class can then be tested with no network.
3. **`maxTokens` is required**: `MessageRequest(model, maxTokens, firstMessage, more*)`. A non-streaming
   `create` refuses a `maxTokens` that could outlast the client timeout (about 21k at the default 10 minutes)
   with `AnthropicError.StreamingRequired`. For large outputs use `stream(...)` or the tool runner, which
   streams internally. 16000 suits `create`, and 64000 suits streaming or agents.
4. **There are no `temperature`, `top_p` or `top_k`.** The current API version rejects them, so they are not
   modeled. Do not add them through `withExtra`. Steer with `withThinking(ThinkingConfig.adaptive)`,
   `withEffort(Effort.High)` and the prompt.
5. **Models are constants**: `Model.ClaudeOpus5_5` (the default choice for agents and the library's own
   examples), `Model.ClaudeFable5_2`, `Model.ClaudeSonnet5_5`, `Model.ClaudeHaiku5_5` (cheap, high volume).
   Never invent an id. `Model.of("...")` exists for ids newer than the SDK.
6. **Check how a reply ended.** For `Message`, check `stopReason` and `isRefusal`. For an agent run, match on
   `result.stop`: `RunStop.Completed | Refused | MaxTokens | MaxIterations | Aborted | Other`. A refusal or a
   token limit is a value to handle. It is not an exception.
7. **A tool is a typed function**: `Tool("snake_name", "what it does and when to use it") { (in: Args) => IO[Out] }`.
   `Args` is a case class whose JSON Schema is derived (`@description` on fields reaches the model). `Out` is a
   `String`, `Unit`, `Json` or any case class. Run tools with `messages.toolRunner(tools[, config]).run(request)`
   rather than writing the loop by hand.
8. **Structured output is `messages.parse[A](request)`**. It returns `Parsed[A]`, so call `.value`, and it fails
   with `AnthropicError.OutputParse` when the reply cannot be decoded. Do not ask for JSON in the prompt and
   parse the text yourself.
9. **Errors are `AnthropicError` subtypes** raised in `F` (`RateLimited`, `Overloaded`, `Api`, `Timeout`,
   `Connection`, ...). Retries (2 by default, with backoff and `retry-after`) are already built in. Do not wrap
   calls in your own retry loop.
10. **Streaming helpers need `import anthropic4s.syntax._`** (`textDeltas`, `finalMessage`, `snapshots`).
11. **Secrets**: the key comes from `ANTHROPIC_API_KEY` or configuration, never a literal. A failed tool's
    exception message goes to the model by default (`RunnerConfig.withOnToolError` changes that). Tracing records
    prompts only with `ContentMode.Content`, which is for demos and never for real user data.
12. **Do not leave `IO`** inside effectful code (`unsafeRunSync`, `Await`). Plain Scala or Play code that needs a
    `Future` uses `FutureAnthropic` instead.

## Where to look next

Read only the reference the task needs. Each one is short, and all of its code compiles against 1.0.0.

| Task | Reference |
|---|---|
| Requests, conversations, system prompts, thinking, caching, stop reasons, token counting | `references/requests.md` |
| Streaming text to a console, a socket or a UI | `references/streaming.md` |
| Case classes back from the model, with circe or Play JSON | `references/structured-outputs.md` |
| Tools, the agent loop, approval gates, events, server tools (web search) | `references/tools-and-agents.md` |
| Errors, retries, timeouts, client configuration, betas | `references/errors-and-config.md` |
| Tests with `ScriptedModel` (no network, no key) | `references/testing.md` |
| Play Framework: `FutureAnthropic`, Guice, controllers, chunked streaming | `references/play.md` |
| OpenTelemetry tracing with otel4s | `references/tracing.md` |
| Message Batches, Models list | `references/batches-and-models.md` |

To start a new agent project from nothing, use the `new-agent` skill (`/anthropic4s:new-agent`). To review
existing anthropic4s code, the `anthropic4s-reviewer` agent checks it against these rules.

## Not covered by 1.0.0

Bedrock, Vertex and Foundry endpoints, Managed Agents, the Admin API and Scala 3 (only the wire model
cross-builds) are not covered. Say so instead of improvising. A request field the SDK does not model yet goes
through `request.withExtra(key, json)`, and a beta goes through `RequestOptions.default.withBetas(...)`.
