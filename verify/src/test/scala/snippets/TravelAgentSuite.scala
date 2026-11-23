package snippets

import anthropic4s._
import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

// snip:start testing
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
// snip:end

class MoreAgentSuite extends CatsEffectSuite {

  test("a booking the user declines never reaches the service") {
    for {
      booked <- Ref.of[IO, Boolean](false)
      bookings = new Bookings {
        def book(restaurant: String, date: String, guests: Int): IO[String] = booked.set(true).as("booked")
      }
      weather = new WeatherService { def lookup(city: String): IO[Weather] = IO.pure(Weather(city, 3, "rain")) }
      model  <- ScriptedModel.of[IO](
        Replies.toolUse("book_table", Json.obj("restaurant" -> "Chez Leon".asJson, "date" -> "2026-12-24".asJson, "guests" -> 2.asJson)),
        Replies.text("No booking made.")
      )
      answer <- new TravelAgent(model, new TravelTools(weather, bookings), _ => IO.pure(false)).ask("Book Chez Leon")
      ran    <- booked.get
    } yield {
      assertEquals(answer, "No booking made.")
      assert(!ran)
    }
  }

  test("a refusal is a value") {
    for {
      model  <- ScriptedModel.of[IO](Replies.stopped(StopReason.Refusal))
      answer <- new TravelAgent(model, new TravelTools(null, null), _ => IO.pure(true)).ask("Something bad")
    } yield assertEquals(answer, "Sorry, I can't help with that.")
  }

  // snip:start testing-structured
  test("structured output, scripted") {
    for {
      model  <- ScriptedModel.of[IO](Replies.structured(Recipe("Pasta al limone", 20, Difficulty.Easy, Nil, None)))
      recipe <- Structured.recipe(model)
    } yield assertEquals(recipe.minutes, 20)
  }
  // snip:end

  // snip:start testing-errors
  test("a scripted failure is raised") {
    for {
      // scripted(...) takes Either: a Left is raised instead of answered.
      model  <- ScriptedModel.scripted[IO](Left(new java.io.IOException("connection reset")))
      result <- model.create(Requests.request).attempt
    } yield assert(result.isLeft)
  }
  // snip:end
}
