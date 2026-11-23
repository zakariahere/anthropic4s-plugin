package snippets

import anthropic4s._
import anthropic4s.schema.description
import cats.effect.IO
import cats.syntax.all._
import io.circe.syntax._

// snip:start tool-types
// Tool arguments are a case class (the schema root must be an object). @description reaches the model.
final case class GetWeather(@description("City name, e.g. Brussels") city: String)
final case class Weather(city: String, celsius: Double, conditions: String)
final case class BookTable(restaurant: String, @description("ISO date, e.g. 2026-12-24") date: String, guests: Int)
// snip:end

// snip:start tools
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
// snip:end

// snip:start agent
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
// snip:end

object AgentEvents {

  // snip:start events
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
  // snip:end

  // snip:start runner-config
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
  // snip:end

  // snip:start server-tools
  // Server tools run on Anthropic's side; add their definitions to the request. They mix with your tools.
  def research(messages: Messages[IO], tools: List[Tool[IO]], question: String): IO[RunResult] = {
    val request = MessageRequest(Model.ClaudeOpus5_5, 64000, MessageParam.user(question))
      .addTools(ToolDefinition.webSearch, ToolDefinition.webFetch)
    messages.toolRunner(tools).run(request)
  }
  // snip:end
}
