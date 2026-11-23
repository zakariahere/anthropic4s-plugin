# Streaming

`messages.stream(request)` is an `fs2.Stream[IO, StreamEvent]`: resource-safe and cancellable. The helpers come
from `anthropic4s.syntax._`.

<!-- snip: Streaming.scala#streaming -->
```scala
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
```

- `textDeltas` gives `Stream[F, String]` with only the text.
- `finalMessage` gives `F[Message]`: the stream folded into the complete reply, with usage and stop reason.
- `snapshots` gives `Stream[F, Message]`: the reply so far, after every event (handy for UIs that re-render).
- Raw events are `StreamEvent.MessageStart`, `ContentBlockStart`, `ContentBlockDelta(index, delta)`,
  `ContentBlockStop`, `MessageDelta` and `MessageStop`. Deltas include `Delta.Text(text)`.

A request is never retried once its response has started. A stream that breaks midway fails with
`AnthropicError.StreamFailure` or `StreamTruncated`.

## When to stream

Stream whenever `maxTokens` is large, because `create` refuses anything that could outlast the timeout:

<!-- snip: Streaming.scala#long-output -->
```scala
// create() refuses a request whose maxTokens could outlast the client timeout (about 21k tokens at the
// default 10 minutes) and raises AnthropicError.StreamingRequired. Large outputs are streamed.
def essay(messages: Messages[IO]): IO[Message] = {
  val request = MessageRequest(Model.ClaudeOpus5_5, maxTokens = 64000, MessageParam.user("Write a long essay."))
    .withThinking(ThinkingConfig.adaptive)
  messages.stream(request).finalMessage
}
```

The tool runner always streams internally. For an agent's text as it is written, use `runner.events(request)`
(see `tools-and-agents.md`). From Play, use `FutureAnthropic.streamText` (see `play.md`).
