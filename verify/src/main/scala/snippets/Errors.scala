package snippets

import anthropic4s._
import cats.effect.{IO, Resource}

import scala.concurrent.duration._

object Errors {

  // snip:start errors
  def answer(messages: Messages[IO], request: MessageRequest): IO[String] =
    messages.create(request).map(_.text).recover {
      case e: AnthropicError.RateLimited => s"Busy; retry in ${e.retryAfter.fold("a moment")(_.toString)}"
      case _: AnthropicError.Overloaded  => "Claude is overloaded; try again shortly."
      case e: AnthropicError.Api         => s"API error ${e.status} ${e.errorType}: ${e.errorMessage} (${e.requestId})"
      case _: AnthropicError.Timeout     => "Timed out."
      case _: AnthropicError.Connection  => "Could not reach the API."
    }
  // snip:end

  // snip:start config
  // One client for the application, configured once.
  def client(apiKey: String): Resource[IO, AnthropicClient[IO]] =
    Anthropic4s.resource[IO](
      AnthropicConfig(apiKey)
        .withMaxRetries(4)          // default 2, with exponential backoff and retry-after
        .withTimeout(2.minutes)     // default 10 minutes
        .withBetas(Beta.Compaction) // sent on every request
    )

  // Or per call:
  def quick(messages: Messages[IO], request: MessageRequest): IO[Message] =
    messages.create(request, RequestOptions.default.withTimeout(30.seconds).withMaxRetries(0))
  // snip:end
}
