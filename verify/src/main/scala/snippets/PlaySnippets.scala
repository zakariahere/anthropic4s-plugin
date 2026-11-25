package snippets.play

// snip:start play-client
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
// snip:end

import anthropic4s.playjson.PlaySchema
import anthropic4s.schema.{description, Schema}
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import play.api.libs.json._
import play.api.mvc._

// snip:start play-structured
final case class Ticket(@description("One-line summary") title: String, urgent: Boolean, assignee: Option[String])

object Ticket {
  implicit val config: JsonConfiguration = PlaySchema.configuration // nulls written, `type` tags: the schema's shape
  implicit val format: OFormat[Ticket]   = Json.format[Ticket]
  implicit val schema: Schema[Ticket]    = PlaySchema.derived[Ticket]
}
// snip:end

final case class FindOrder(@description("Order number") id: String)
trait OrderRepository { def find(id: String): Future[Option[String]] }

class ChatController @Inject() (cc: ControllerComponents, claude: Claude, orders: OrderRepository)(implicit
    ec: ExecutionContext,
    mat: Materializer
) extends AbstractController(cc) {

  private def request(question: String) = MessageRequest(Model.ClaudeOpus5_5, 16000, MessageParam.user(question))

  // snip:start play-errors
  def ask(question: String): Action[AnyContent] = Action.async {
    claude.ask(question).map(Ok(_)).recover {
      case _: AnthropicError.RateLimited => TooManyRequests("Busy, try again shortly")
      case e: AnthropicError.Api         => BadGateway(s"Claude said ${e.status}")
      case _: AnthropicError.Timeout     => GatewayTimeout
    }
  }
  // snip:end

  // snip:start play-structured-call
  def fileTicket(text: String): Future[Ticket] =
    claude.anthropic.parse[Ticket](request(s"File a ticket: $text")).map(_.value)
  // snip:end

  // snip:start play-streaming
  def chat(question: String): Action[AnyContent] = Action {
    val (queue, source) = Source.queue[String](64).preMaterialize()
    claude.anthropic
      .streamText(request(question))(text => queue.offer(text)) // runs on the SDK's threads: never block here
      .onComplete(_ => queue.complete())
    Ok.chunked(source)
  }
  // snip:end

  // snip:start play-tools
  val findOrder: Tool[cats.effect.IO] =
    FutureAnthropic.tool("find_order", "Looks an order up by its number") { (in: FindOrder) =>
      orders.find(in.id).map(_.getOrElse("No such order")) // Future[String]
    }

  def support(question: String): Future[String] =
    claude.anthropic.runTools(request(question), List(findOrder)).map(_.text)
  // snip:end
}

// snip:start play-decide
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
// snip:end
