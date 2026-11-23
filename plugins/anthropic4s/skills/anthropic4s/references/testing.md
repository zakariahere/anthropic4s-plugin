# Testing with the testkit

`anthropic4s-testkit` (add it `% Test`) has `ScriptedModel`, a `Messages[IO]` that answers each request with
the next scripted reply and records what it was sent. It works for `create`, `stream`, `parse` and the tool
runner, with no network and no API key. That is why application code should take `Messages[IO]` rather than a
client.

<!-- snip: TravelAgentSuite.scala#testing -->
```scala
import anthropic4s.testkit.{Replies, ScriptedModel}
import io.circe.Json
import io.circe.syntax._

class TravelAgentSuite extends CatsEffectSuite {

  private val sunny = new WeatherService {
    def lookup(city: String): IO[Weather] = IO.pure(Weather(city, 21.0, "sunny"))
  }
  private val noBookings = new Bookings {
    def book(restaurant: String, date: String, guests: Int): IO[String] = IO.raiseError(new Exception("unexpected"))
  }

  test("checks the weather, then answers") {
    for {
      // The scripted model answers each request with the next reply. No network, no API key.
      model <- ScriptedModel.of[IO](
        Replies.toolUse("get_weather", Json.obj("city" -> "Brussels".asJson)),
        Replies.text("Yes, eat outside: 21 °C and sunny.")
      )
      agent   = new TravelAgent(model, new TravelTools(sunny, noBookings), _ => IO.pure(false))
      answer <- agent.ask("Can I eat outside in Brussels tonight?")
      sent   <- model.requests // what the agent sent, request by request
    } yield {
      assertEquals(answer, "Yes, eat outside: 21 °C and sunny.")
      assertEquals(sent.size, 2)
      assert(sent.head.tools.exists(_.name == "get_weather"))
      // The second request carries the tool result back to the model.
      assert(sent(1).messages.last.content.blocks.exists {
        case r: ContentBlock.ToolResult => r.text.contains("sunny")
        case _                          => false
      })
    }
  }
}
```

The reply builders in `Replies` are:

- `Replies.text(text)`: a plain answer, ending with `end_turn`.
- `Replies.toolUse(name, inputJson, id = "toolu_1")`: one tool call.
- `Replies.toolUses((id, name, inputJson), ...)`: several calls in one turn, which the runner runs concurrently.
- `Replies.structured(value)`: a structured-output reply for `parse[A]`.
- `Replies.stopped(StopReason.Refusal | MaxTokens | ...)`: a reply that ended some other way.

<!-- snip: TravelAgentSuite.scala#testing-structured -->
```scala
test("structured output, scripted") {
  for {
    model  <- ScriptedModel.of[IO](Replies.structured(Recipe("Pasta al limone", 20, Difficulty.Easy, Nil, None)))
    recipe <- Structured.recipe(model)
  } yield assertEquals(recipe.minutes, 20)
}
```

<!-- snip: TravelAgentSuite.scala#testing-errors -->
```scala
test("a scripted failure is raised") {
  for {
    // scripted(...) takes Either: a Left is raised instead of answered.
    model  <- ScriptedModel.scripted[IO](Left(new java.io.IOException("connection reset")))
    result <- model.create(Requests.request).attempt
  } yield assert(result.isLeft)
}
```

What is worth asserting:

- the final answer, or the `RunStop` the code maps it to;
- `model.requests`: the tools offered, the system prompt, and the tool results sent back;
- that a denied or gated tool never touched the real service (use a `Ref` flag in a fake);
- that the script ran out exactly. A missing reply fails with "ScriptedModel has no reply left".

Run them with `sbt test`. A real-model smoke test is separate and opt-in, because it needs a key and spends
tokens.
