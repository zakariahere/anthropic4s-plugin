# Decision models with anthropic4s-decide

`anthropic4s-decide` (since 1.1.0) is a client for **decision models**: small models that pick an answer and
return calibrated probabilities instead of writing text. It speaks the System One protocol
(`POST /v1/systemone`), served for example by llama-server running Kev-4B. A warm answer takes tens of
milliseconds and costs no output tokens. It is the right tool for classification, routing and yes/no checks;
Claude stays the right tool for anything that needs reasoning or text.

```scala
libraryDependencies += "io.github.zakariahere" %% "anthropic4s-decide" % "1.1.0"
```

It does not depend on the rest of anthropic4s, so a classification-only project needs nothing else. Everything
is in `import anthropic4s.decide._`. There is no `DecisionClient`, no `classify(...)` and no string-typed
answer: questions are typed values, and each one decodes its own answer type.

<!-- snip: Decisions.scala#decide-imports -->
```scala
import anthropic4s._
import anthropic4s.decide._
import cats.effect.IO
import cats.syntax.all._
import io.circe.Json
import io.circe.syntax._
```

## Questions

<!-- snip: Decisions.scala#decide-questions -->
```scala
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
```

| Question | Answer type | What it holds |
|---|---|---|
| `Question.choice[A](instructions)(c1, c2, more*)` | `ChoiceAnswer[A]` | `choice: A`, `probabilities: Map[A, Double]`, `confidence` |
| `Question.score(instructions)(lowest, next, higher*)` | `ScoreAnswer` | `score` (weighted index), `nearest`, `mostLikely`, `probabilities`, `confidence` |
| `Question.noul(instructions)` | `Double` | the probability that the statement is true |

- A choice needs at least two `Criterion`s; the signature enforces it. Labels must be unique.
- A score needs 2 to 10 levels, lowest first. Levels are a `Vector`, never a `Map`: order is the meaning.
- `.describe(whenTrue, whenFalse)` on a noul says what true and false mean. It separates answers noticeably.
- Invalid questions fail with `DecideError.InvalidQuestion` **before** anything is sent.

## Asking

<!-- snip: Decisions.scala#decide-ask -->
```scala
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
```

- `ask(state, question)` sends one question. `run(state, batch)` sends several in one request; build the batch
  with `q.as("id")` and combine with `.tupled` (needs `cats.syntax.all._`). Ids must be unique.
- `state` is a `String` or any `Json`, including a conversation:
  `Json.arr(Json.obj("role" -> "user".asJson, "content" -> text.asJson), ...)`.
- Several `ask`s in parallel (`parTupled`) can be faster than one batch. Measure before choosing.

## Configuration

<!-- snip: Decisions.scala#decide-config -->
```scala
val local: DecideConfig  = DecideConfig.local()                     // http://localhost:8081/v1/systemone
def remote(apiKey: String): DecideConfig =
  DecideConfig(org.http4s.Uri.unsafeFromString("https://decide.example.com/kev")) // + /v1/systemone
    .withApiKey(apiKey)      // sent as a Bearer token, never printed by toString
    .withTimeout(30.seconds) // the default is 10 s, enough for a cold llama-server

// Reads the optional DECIDE_BASE_URL, DECIDE_API_KEY and DECIDE_TIMEOUT (e.g. "30s"). With none set it is
// `local()`. A value it cannot read fails with DecideError.Configuration.
val fromEnv: IO[DecideConfig] = DecideConfig.fromEnv[IO]
```

`DecideConfig(baseUrl)` appends `/v1/systemone` (trailing slashes and path prefixes are fine); `withEndpoint`
replaces the whole endpoint for a server with another route. `Decide.ember[IO](config)` is a `Resource` with
its own connection pool. Create one `Decide` per application, like the Claude client.

With anthropic4s in the same application, share the HTTP client:

<!-- snip: Decisions.scala#decide-shared-client -->
```scala
// Already using anthropic4s? Build both clients on one http4s client: one pool, one set of middleware.
def clients(http: Client[IO]): IO[(AnthropicClient[IO], Decide[IO])] =
  (AnthropicConfig.fromEnv[IO], DecideConfig.fromEnv[IO]).mapN { (claude, decide) =>
    (AnthropicClient.fromClient(http, claude), Decide.fromClient(http, decide))
  }
```

## Confidence gates: the small model first, Claude when unsure

<!-- snip: Decisions.scala#decide-gate -->
```scala
// Act on the cheap answer when it is confident; otherwise ask Claude.
def route(decide: Decide[IO], claude: Messages[IO], ticket: String): IO[String] =
  decide.ask(ticket, team).flatMap { answer =>
    if (answer.confidence >= 0.8) IO.pure(s"routed to ${answer.choice}")
    else
      claude
        .create(MessageRequest(Model.ClaudeHaiku5_5, 1024, MessageParam.user(s"Which team should handle: $ticket")))
        .map(_.text)
  }
```

`confidence` is **not** the top probability. It is `(p_top − 1/n) / (1 − 1/n)`, so a 0.53 favourite among three
options is only 0.29 confident, while the same 0.53 on a yes/no is barely above chance. Pick thresholds per
question. An unsure answer that goes to Claude costs money but is still correct; a confident wrong one is not.

## Guarding tools in an agent

The tool runner's `withBeforeTool` hook can ask the decision model before a side effect runs:

<!-- snip: Decisions.scala#decide-guard -->
```scala
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
```

- `ToolDecision.Deny(reason)` does not run the tool and sends `reason` to Claude as an error tool result; the
  run continues. `Abort` would stop the whole run.
- The guard sees only the call, so close over what the user wrote. Fail closed with `handleError`: "could not
  check" must mean "do not do it".

## Errors

Every failure is a `DecideError`: `InvalidQuestion`, `Http(status, body)`, `Decode` (an answer that does not fit
the question), `Timeout`, `Unavailable(endpoint, cause)` (refused connection, unknown host) and
`Configuration` (from `fromEnv`).

<!-- snip: Decisions.scala#decide-errors -->
```scala
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
```

## Play and other `Future` code

`FutureDecide` is the `Future` facade, like `FutureAnthropic`: `fromEnv()`, `local(port)`, `start(config)`,
`wrap(decide, shutdown)`, then `ask`, `run` and `close()`.

<!-- snip: PlaySnippets.scala#play-decide -->
```scala
import anthropic4s.decide.{DecideError, FutureDecide}
import cats.syntax.all._ // for .tupled on batches
import snippets.Triage.{team, urgency}

@Singleton
class TicketTriage @Inject() (lifecycle: ApplicationLifecycle)(implicit ec: ExecutionContext) {

  // Reads DECIDE_BASE_URL, DECIDE_API_KEY and DECIDE_TIMEOUT, all optional (default: llama-server on :8081).
  private val decide = FutureDecide.fromEnv()
  lifecycle.addStopHook(() => decide.close())

  def classify(ticket: String): Future[JsObject] =
    decide
      .run(ticket, (team.as("team"), urgency.as("urgency")).tupled) // one request, two typed answers
      .map { case (t, u) =>
        Json.obj("team" -> t.choice.toString, "urgency" -> u.mostLikely, "needsHuman" -> (t.confidence < 0.8))
      }
      // The decision model is a shortcut, not a dependency: if it is down, a human triages.
      .recover { case e: DecideError => Json.obj("error" -> e.getMessage, "needsHuman" -> true) }
}
```

## Testing

A fake decision model is an http4s `Client` built from an `HttpApp`; together with `ScriptedModel`, a whole
guarded agent run needs no network:

<!-- snip: DecisionSuite.scala#decide-testing -->
```scala
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
```

Count what a side-effecting tool really did (`Ref`), rather than only checking that the guard returned `Deny`.
