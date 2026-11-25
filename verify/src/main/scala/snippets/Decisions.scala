package snippets

// snip:start decide-imports
import anthropic4s._
import anthropic4s.decide._
import cats.effect.IO
import cats.syntax.all._
import io.circe.Json
import io.circe.syntax._
// snip:end

import org.http4s.client.Client

import scala.concurrent.duration._

// snip:start decide-questions
sealed trait Team
object Team {
  case object Billing   extends Team
  case object Shipping  extends Team
  case object Technical extends Team
}

object Triage {
  // A choice: the model picks one option. Describe what each option needs, not only what it is about.
  val team: Question.Choice[Team] = Question.choice[Team]("Which team should handle this?")(
    Criterion(Team.Billing, "billing", "payments, charges, refunds, invoices"),
    Criterion(Team.Shipping, "shipping", "delivery, tracking, lost or late parcels"),
    Criterion(Team.Technical, "technical", "bugs, errors, login problems")
  )

  // A yes/no question ("noul"). The answer is the probability that it is true.
  val angry: Question.Noul = Question
    .noul("Is the customer angry?")
    .describe(whenTrue = "the customer is upset, hostile or threatening", whenFalse = "the customer is calm")

  // A score: 2 to 10 ordered levels, lowest first.
  val urgency: Question.Score =
    Question.score("How urgent is this?")("can wait", "this week", "today", "right now")
}
// snip:end

object DecideBasics {
  import Triage._

  // snip:start decide-ask
  def triage(ticket: String): IO[Unit] =
    Decide.ember[IO](DecideConfig.local()).use { decide => // llama-server on localhost:8081
      for {
        // One question, one request: IO[ChoiceAnswer[Team]]
        route <- decide.ask(ticket, team)
        // Several questions in one request, typed per question: IO[(Double, ScoreAnswer)]
        both  <- decide.run(ticket, (angry.as("angry"), urgency.as("urgency")).tupled)
        (a, u) = both
        _     <- IO.println(f"${route.choice} (confidence ${route.confidence}%.2f), angry $a%.2f")
        // `nearest` rounds the weighted score; `mostLikely` is the single most probable level. They can differ.
        _     <- IO.println(s"urgency ${u.nearest}, most likely ${u.mostLikely}")
      } yield ()
    }
  // snip:end

  // snip:start decide-config
  val local: DecideConfig  = DecideConfig.local()                     // http://localhost:8081/v1/systemone
  def remote(apiKey: String): DecideConfig =
    DecideConfig(org.http4s.Uri.unsafeFromString("https://decide.example.com/kev")) // + /v1/systemone
      .withApiKey(apiKey)      // sent as a Bearer token, never printed by toString
      .withTimeout(30.seconds) // the default is 10 s, enough for a cold llama-server

  // Reads the optional DECIDE_BASE_URL, DECIDE_API_KEY and DECIDE_TIMEOUT (e.g. "30s"). With none set it is
  // `local()`. A value it cannot read fails with DecideError.Configuration.
  val fromEnv: IO[DecideConfig] = DecideConfig.fromEnv[IO]
  // snip:end

  // snip:start decide-shared-client
  // Already using anthropic4s? Build both clients on one http4s client: one pool, one set of middleware.
  def clients(http: Client[IO]): IO[(AnthropicClient[IO], Decide[IO])] =
    (AnthropicConfig.fromEnv[IO], DecideConfig.fromEnv[IO]).mapN { (claude, decide) =>
      (AnthropicClient.fromClient(http, claude), Decide.fromClient(http, decide))
    }
  // snip:end

  // snip:start decide-gate
  // Act on the cheap answer when it is confident; otherwise ask Claude.
  def route(decide: Decide[IO], claude: Messages[IO], ticket: String): IO[String] =
    decide.ask(ticket, team).flatMap { answer =>
      if (answer.confidence >= 0.8) IO.pure(s"routed to ${answer.choice}")
      else
        claude
          .create(MessageRequest(Model.ClaudeHaiku5_5, 1024, MessageParam.user(s"Which team should handle: $ticket")))
          .map(_.text)
    }
  // snip:end

  // snip:start decide-errors
  def teamOrHuman(decide: Decide[IO], ticket: String): IO[Either[String, Team]] =
    decide
      .ask(ticket, team)
      .map(answer => Right(answer.choice): Either[String, Team])
      .recover {
        case _: DecideError.Unavailable => Left("decision model unreachable: a human triages")
        case _: DecideError.Timeout     => Left("decision model too slow: a human triages")
        case e: DecideError.Http        => Left(s"decision model answered ${e.status}")
        // InvalidQuestion (checked before sending), Decode and Configuration are bugs to fix, not to route around.
      }
  // snip:end
}

object DecideGuard {

  // snip:start decide-guard
  val bookingAsked: Question.Noul = Question
    .noul("Did the user ask for a restaurant booking?")
    .describe(
      whenTrue = "the user asks to book, reserve or get a table somewhere, even conditionally",
      whenFalse = "the user only asks for information or advice"
    )

  /** The state a System One server reads as a conversation. */
  def conversation(userText: String): Json =
    Json.arr(Json.obj("role" -> "user".asJson, "content" -> userText.asJson))

  // Checks a booking against what the user wrote, before it runs. A Deny goes back to Claude as a tool error,
  // so the agent changes course. It fails closed: if the decision model cannot answer, nothing is booked.
  def bookingGuard(decide: Decide[IO], userText: String): ContentBlock.ToolUse => IO[ToolDecision] = call =>
    if (call.name != "book_table") IO.pure(ToolDecision.Allow)
    else
      decide
        .ask(conversation(userText), bookingAsked)
        .map { p =>
          if (p >= 0.5) ToolDecision.Allow
          else ToolDecision.Deny("The user did not ask for a booking. Do not book anything.")
        }
        .handleError(_ => ToolDecision.Deny("Bookings cannot be checked right now."))

  def config(decide: Decide[IO], userText: String): RunnerConfig[IO] =
    RunnerConfig.default[IO].withMaxIterations(10).withBeforeTool(bookingGuard(decide, userText))
  // snip:end
}
