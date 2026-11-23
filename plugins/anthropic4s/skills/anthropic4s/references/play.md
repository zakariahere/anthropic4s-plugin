# From Play Framework

Play code works with `Future`, and `FutureAnthropic` sits between it and the Cats Effect SDK, so a Play
application never touches `IO`. Unlike the official Java SDK, it needs no `dependencyOverrides`: there is no
Kotlin runtime and no Jackson.

```scala
libraryDependencies ++= Seq(
  "io.github.zakariahere" %% "anthropic4s"           % "1.0.0",
  "io.github.zakariahere" %% "anthropic4s-play-json" % "1.0.0" // only for structured outputs with Formats
)
```

## One client for the application

<!-- snip: PlaySnippets.scala#play-client -->
```scala
import anthropic4s._
import javax.inject.{Inject, Singleton}
import play.api.inject.ApplicationLifecycle
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class Claude @Inject() (lifecycle: ApplicationLifecycle)(implicit ec: ExecutionContext) {

  // Reads ANTHROPIC_API_KEY. FutureAnthropic.start(AnthropicConfig(key)) takes it from Play config instead.
  // Starting it opens no connection, so a constructor is a fine place.
  val anthropic: FutureAnthropic = FutureAnthropic.fromEnv()
  lifecycle.addStopHook(() => anthropic.close())

  def ask(question: String): Future[String] =
    anthropic.create(MessageRequest(Model.ClaudeOpus5_5, 16000, MessageParam.user(question))).map(_.text)
}
```

It is a `@Singleton` with a stop hook, so there is one connection pool that closes with the application. Do
not create a `FutureAnthropic` per request or per controller call.

## Errors in a controller

<!-- snip: PlaySnippets.scala#play-errors -->
```scala
def ask(question: String): Action[AnyContent] = Action.async {
  claude.ask(question).map(Ok(_)).recover {
    case _: AnthropicError.RateLimited => TooManyRequests("Busy, try again shortly")
    case e: AnthropicError.Api         => BadGateway(s"Claude said ${e.status}")
    case _: AnthropicError.Timeout     => GatewayTimeout
  }
}
```

## Structured outputs with your `Format`

<!-- snip: PlaySnippets.scala#play-structured -->
```scala
final case class Ticket(@description("One-line summary") title: String, urgent: Boolean, assignee: Option[String])

object Ticket {
  implicit val config: JsonConfiguration = PlaySchema.configuration // nulls written, `type` tags: the schema's shape
  implicit val format: OFormat[Ticket]   = Json.format[Ticket]
  implicit val schema: Schema[Ticket]    = PlaySchema.derived[Ticket]
}
```

<!-- snip: PlaySnippets.scala#play-structured-call -->
```scala
def fileTicket(text: String): Future[Ticket] =
  claude.anthropic.parse[Ticket](request(s"File a ticket: $text")).map(_.value)
```

## Streaming to the browser

<!-- snip: PlaySnippets.scala#play-streaming -->
```scala
def chat(question: String): Action[AnyContent] = Action {
  val (queue, source) = Source.queue[String](64).preMaterialize()
  claude.anthropic
    .streamText(request(question))(text => queue.offer(text)) // runs on the SDK's threads: never block here
    .onComplete(_ => queue.complete())
  Ok.chunked(source)
}
```

`FutureAnthropic.stream(request)(onEvent)` gives every raw event instead. Both complete with the whole
`Message`.

## Tools

<!-- snip: PlaySnippets.scala#play-tools -->
```scala
val findOrder: Tool[cats.effect.IO] =
  FutureAnthropic.tool("find_order", "Looks an order up by its number") { (in: FindOrder) =>
    orders.find(in.id).map(_.getOrElse("No such order")) // Future[String]
  }

def support(question: String): Future[String] =
  claude.anthropic.runTools(request(question), List(findOrder)).map(_.text)
```

`runTools(request, tools, config)` takes a `RunnerConfig[IO]` for approval gates and limits.

## Going further

`anthropic.client` is the full `AnthropicClient[IO]`, and `anthropic.run(io)` turns any SDK effect into a
`Future`. Use them for Batches, Files and Models: `anthropic.run(anthropic.client.models.listAll.compile.toList)`.
