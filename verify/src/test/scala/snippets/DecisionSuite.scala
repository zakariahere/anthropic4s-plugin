package snippets

import anthropic4s._
import anthropic4s.decide._
import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

// snip:start decide-testing
import anthropic4s.testkit.{Replies, ScriptedModel}
import io.circe.Json
import io.circe.syntax._
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.{HttpApp, Response, Status}

class DecisionSuite extends CatsEffectSuite {

  // A fake decision model: every question gets this answer, over HTTP, with no server running.
  private def decideAnswering(answer: Json): Decide[IO] =
    Decide.fromClient(
      Client.fromHttpApp(HttpApp[IO](_ => IO.pure(Response[IO](Status.Ok).withEntity(Json.obj("answers" -> answer))))),
      DecideConfig.local()
    )
  private def noul(p: Double): Json = Json.obj("q" -> Json.obj("noul" -> p.asJson))

  private val decideDown: Decide[IO] = Decide.fromClient(
    Client.fromHttpApp(HttpApp[IO](_ => IO.pure(Response[IO](Status.ServiceUnavailable)))),
    DecideConfig.local()
  )

  // Claude tries to book; the real guard decides; the booking tool only counts how often it really ran.
  private def bookings(decide: Decide[IO], userText: String): IO[Int] =
    for {
      count <- Ref[IO].of(0)
      claude <- ScriptedModel.of[IO](
                  Replies.toolUse(
                    "book_table",
                    Json.obj("restaurant" -> "Chez Leon".asJson, "date" -> "2026-12-24".asJson, "guests" -> 2.asJson)
                  ),
                  Replies.text("Done.")
                )
      bookTable = Tool("book_table", "Books a table.")((_: BookTable) => count.update(_ + 1).as("Booked."))
      _ <- claude
             .toolRunner(List(bookTable), DecideGuard.config(decide, userText))
             .run(MessageRequest(Model.ClaudeOpus5_5, 1024, MessageParam.user(userText)))
      n <- count.get
    } yield n

  test("an unrequested booking is denied and never runs") {
    bookings(decideAnswering(noul(0.11)), "Should I eat outside tonight?").map(n => assertEquals(n, 0))
  }

  test("a requested booking runs") {
    bookings(decideAnswering(noul(0.70)), "Book Chez Leon for two.").map(n => assertEquals(n, 1))
  }

  test("it fails closed: if the decision model is down, nothing is booked") {
    bookings(decideDown, "Book Chez Leon for two.").map(n => assertEquals(n, 0))
  }
}
// snip:end

class DecisionGateSuite extends CatsEffectSuite {
  import DecisionSuiteSupport._

  test("a confident answer is used and Claude is not called") {
    for {
      claude <- ScriptedModel.of[IO]() // no replies: any call to Claude would fail the test
      routed <- DecideBasics.route(choosing("billing", 0.92), claude, "I was charged twice")
      sent   <- claude.requests
    } yield {
      assertEquals(routed, "routed to Billing")
      assertEquals(sent.size, 0)
    }
  }

  test("an unsure answer goes to Claude") {
    for {
      claude <- ScriptedModel.of[IO](Replies.text("billing"))
      routed <- DecideBasics.route(choosing("billing", 0.30), claude, "Something is wrong with my order")
    } yield assertEquals(routed, "billing")
  }

  test("an unreachable decision model is a value, not a crash") {
    DecideBasics
      .teamOrHuman(Decide.fromClient(refused, DecideConfig.local()), "I was charged twice")
      .map(r => assertEquals(r, Left("decision model unreachable: a human triages")))
  }
}

object DecisionSuiteSupport {
  import cats.effect.Resource
  import io.circe.Json
  import io.circe.syntax._
  import org.http4s.circe._
  import org.http4s.client.Client
  import org.http4s.{HttpApp, Response, Status}

  def choosing(label: String, confidence: Double): Decide[IO] = {
    val answer = Json.obj(
      "answers" -> Json.obj(
        "q" -> Json.obj(
          "choice"        -> label.asJson,
          "probabilities" -> Json.obj(label -> 1.0.asJson),
          "confidence"    -> confidence.asJson
        )
      )
    )
    Decide.fromClient(
      Client.fromHttpApp(HttpApp[IO](_ => IO.pure(Response[IO](Status.Ok).withEntity(answer)))),
      DecideConfig.local()
    )
  }

  val refused: Client[IO] =
    Client[IO](_ => Resource.raiseError[IO, Response[IO], Throwable](new java.net.ConnectException("refused")))
}
