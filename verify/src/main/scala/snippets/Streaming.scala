package snippets

import anthropic4s._
import cats.effect.IO

import scala.concurrent.duration._

// snip:start streaming
import anthropic4s.syntax._ // textDeltas, finalMessage, snapshots

object Streaming {

  // Print the reply as it is written.
  def printLive(messages: Messages[IO], request: MessageRequest): IO[Unit] =
    messages.stream(request).textDeltas.evalMap(IO.print).compile.drain

  // Show the text as it arrives and keep the complete reply (usage, stop reason) at the end.
  def printAndKeep(messages: Messages[IO], request: MessageRequest): IO[Message] =
    messages
      .stream(request)
      .evalTap {
        case StreamEvent.ContentBlockDelta(_, Delta.Text(text)) => IO.print(text)
        case _                                                  => IO.unit
      }
      .finalMessage

  // Stopping early (take, a timeout, cancellation) closes the connection.
  def firstWords(messages: Messages[IO], request: MessageRequest): IO[String] =
    messages.stream(request).textDeltas.interruptAfter(5.seconds).compile.string
}
// snip:end

object LongOutput {

  // snip:start long-output
  // create() refuses a request whose maxTokens could outlast the client timeout (about 21k tokens at the
  // default 10 minutes) and raises AnthropicError.StreamingRequired. Large outputs are streamed.
  def essay(messages: Messages[IO]): IO[Message] = {
    val request = MessageRequest(Model.ClaudeOpus5_5, maxTokens = 64000, MessageParam.user("Write a long essay."))
      .withThinking(ThinkingConfig.adaptive)
    messages.stream(request).finalMessage
  }
  // snip:end
}
