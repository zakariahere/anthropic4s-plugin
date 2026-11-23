# Tools and agents

An agent in anthropic4s has three parts: typed tools, a request, and the `ToolRunner`, which runs the loop.
Do not write the loop by hand unless the runner cannot express what you need.

## Tools

<!-- snip: Agents.scala#tool-types -->
```scala
// Tool arguments are a case class (the schema root must be an object). @description reaches the model.
final case class GetWeather(@description("City name, e.g. Brussels") city: String)
final case class Weather(city: String, celsius: Double, conditions: String)
final case class BookTable(restaurant: String, @description("ISO date, e.g. 2026-12-24") date: String, guests: Int)
```

<!-- snip: Agents.scala#tools -->
```scala
trait WeatherService { def lookup(city: String): IO[Weather] }
trait Bookings       { def book(restaurant: String, date: String, guests: Int): IO[String] }

final class TravelTools(weather: WeatherService, bookings: Bookings) {

  // The function's result is sent back to the model: a String as text, a case class as JSON.
  val getWeather: Tool[IO] =
    Tool("get_weather", "Current weather for a city")((in: GetWeather) => weather.lookup(in.city))

  val bookTable: Tool[IO] =
    Tool("book_table", "Books a restaurant table. Costs money: only when the user asked for a booking.") {
      (in: BookTable) => bookings.book(in.restaurant, in.date, in.guests)
    }

  val all: List[Tool[IO]] = List(getWeather, bookTable)
}
```

- The argument schema is derived from the case class and sent as a **strict** tool, so the arguments are
  decoded before your function runs. A runner refuses to start (`AnthropicError.InvalidSchema`) when a type has
  no strict schema, for example when the root is not a case class.
- Bad arguments, or a function that fails, become an **error result** the model sees and can react to. They do
  not fail the run.
- Names are `snake_case`. The description says what the tool does **and when to use it**, and it is the main
  lever on tool choice. Name any side effects ("Costs money", "Sends an email").
- `Tool.raw(definition)(call => ...)` takes a hand-written `ToolDefinition.Custom` and the raw call, when the
  derived schema does not fit.

## The agent

<!-- snip: Agents.scala#agent -->
```scala
/** Depends on Messages[IO], not on a client, so a test can hand it a ScriptedModel. */
final class TravelAgent(
    messages: Messages[IO],
    tools: TravelTools,
    confirmBooking: ContentBlock.ToolUse => IO[Boolean]
) {

  private val config: RunnerConfig[IO] =
    RunnerConfig
      .default[IO]
      .withMaxIterations(10) // the most model requests one run may make
      .withBeforeTool { call => // an approval gate, called before every tool call
        if (call.name == "book_table")
          confirmBooking(call).map(ok => if (ok) ToolDecision.Allow else ToolDecision.Deny("The user declined."))
        else IO.pure(ToolDecision.Allow)
      }

  def ask(question: String): IO[String] = {
    val request = MessageRequest(Model.ClaudeOpus5_5, maxTokens = 64000, MessageParam.user(question))
      .withSystem("You help people plan an evening out. Check the weather before suggesting a terrace.")
      .withThinking(ThinkingConfig.adaptive)

    messages.toolRunner(tools.all, config).run(request).map { result =>
      // How the run ended is a value, never an exception.
      result.stop match {
        case RunStop.Completed       => result.text
        case RunStop.Refused(_)      => "Sorry, I can't help with that."
        case RunStop.MaxTokens       => s"${result.text} (the answer was cut off)"
        case RunStop.MaxIterations   => "That took too many steps; please narrow the question."
        case RunStop.Aborted(reason) => s"Stopped: $reason"
        case RunStop.Other(reason)   => s"Stopped: ${reason.value}"
      }
    }
  }
}
```

`runner.run(request)` returns `IO[RunResult]`, which holds `message` (the final reply), `transcript` (every
turn), `usage` (summed over all requests), `iterations` and `stop`. `runner.turns(request)` streams each model
reply.

How the runner behaves:

- Tool calls from one turn run **concurrently** (up to 8). Their results go back in order, in one user message.
- On `RunStop.Refused` or `RunStop.MaxTokens`, that turn's tools are **not** run.
- Cancelling the effect or the stream cancels the tools that are running.
- Every request is streamed, so a large `maxTokens` (64000) is fine.

## Watching a run

<!-- snip: Agents.scala#events -->
```scala
// The same run as a stream of events: text as it is written, each tool call and its result, the outcome.
def watch(messages: Messages[IO], tools: List[Tool[IO]], request: MessageRequest): IO[Unit] =
  messages
    .toolRunner(tools)
    .events(request)
    .evalMap {
      case RunEvent.Model(_, StreamEvent.ContentBlockDelta(_, Delta.Text(text))) => IO.print(text)
      case RunEvent.ToolStarted(call)       => IO.println(s"\n[tool] ${call.name} ${call.input.asJson.noSpaces}")
      case RunEvent.ToolFinished(_, result) => IO.println(s"[tool] -> ${result.text}")
      case RunEvent.Finished(result)        =>
        IO.println(s"\n${result.stop} after ${result.iterations} turns, ${result.usage.show}")
      case _ => IO.unit
    }
    .compile
    .drain
```

`RunEvent`s arrive in order: `Model(iteration, streamEvent)`, `TurnCompleted`, `ToolStarted` (only for allowed
calls), `ToolFinished` (whether the call ran, was denied or was replaced), then always `Finished(result)` last.

## RunnerConfig

<!-- snip: Agents.scala#runner-config -->
```scala
def config(askHuman: ContentBlock.ToolUse => IO[Boolean]): RunnerConfig[IO] =
  RunnerConfig
    .default[IO]
    .withMaxIterations(15)
    .withMaxConcurrency(1) // tool calls of one turn run concurrently by default (8); 1 = strictly serial
    .withBeforeTool {
      case call if call.name.startsWith("delete_") =>
        askHuman(call).map(ok => if (ok) ToolDecision.Allow else ToolDecision.Deny("The user said no."))
      case _ => IO.pure(ToolDecision.Allow)
    }
    // The default reports the exception's class and message to the model. Keep secrets out of it:
    .withOnToolError((call, _) => IO.pure(ContentBlock.ToolResult.error(call.id, "The tool failed.")))
```

`withBeforeTool` returns a `ToolDecision`:

- `Allow` runs the tool.
- `Deny(reason)` sends the reason to the model instead.
- `Replace(result)` answers with a value you supply (a cache hit, a human's answer).
- `Abort(reason)` stops the run with `RunStop.Aborted`.

`withBeforeRequest((iteration, request) => ...)` edits each request before it is sent (trim history, add
context). `withRequestOptions` applies options such as betas to every request of the run.

## Server tools

<!-- snip: Agents.scala#server-tools -->
```scala
// Server tools run on Anthropic's side; add their definitions to the request. They mix with your tools.
def research(messages: Messages[IO], tools: List[Tool[IO]], question: String): IO[RunResult] = {
  val request = MessageRequest(Model.ClaudeOpus5_5, 64000, MessageParam.user(question))
    .addTools(ToolDefinition.webSearch, ToolDefinition.webFetch)
  messages.toolRunner(tools).run(request)
}
```

The other server tools are `ToolDefinition.codeExecution`, `bash`, `textEditor` and `memory`. The API runs web
search and fetch. For the client-side tool types (bash, text editor, memory), your code has to do what the
model asks, so use them only when you mean to.

## Designing an agent

- Give it a short system prompt that states the job, what the tools are for and when to stop.
- Use few tools with sharp names, and return compact JSON results (a case class) rather than prose.
- Gate side effects with `withBeforeTool`. Keep `withMaxIterations` low (10–15) unless the task needs more.
- Test the loop with `ScriptedModel` (`testing.md`) before spending tokens.
