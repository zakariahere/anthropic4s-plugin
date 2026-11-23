# Requests and replies

Most files start with:

<!-- snip: Basics.scala#imports -->
```scala
import anthropic4s._
import cats.effect.{IO, IOApp}
import cats.syntax.all._
```

## Building a request

`MessageRequest(model, maxTokens, first, rest*)` takes one or more `MessageParam`s (`MessageParam.user(text)`,
`MessageParam.assistant(text)`, or blocks). Everything else is a `withX` method, and SDK types have no public
`copy`.

<!-- snip: Basics.scala#request-builders -->
```scala
val request: MessageRequest =
  MessageRequest(Model.ClaudeOpus5_5, maxTokens = 16000, MessageParam.user("Summarise this contract: ..."))
    .withSystem("You are a careful paralegal. Answer in plain English.")
    .withThinking(ThinkingConfig.adaptive) // the model decides how much to think
    .withEffort(Effort.High)
    .withStopSequences(List("</answer>"))
```

Also available: `withTools`, `addTools`, `withToolChoice(ToolChoice.auto | any | none | tool(name))`,
`withOutputConfig`, `withServiceTier`, `withUserId`, `withCacheControl`, `withFallbacks(Fallbacks.Default)`.
`ThinkingConfig.adaptive` is the normal choice; there are also `ThinkingConfig.enabled(budgetTokens)` and
`ThinkingConfig.disabled`. `Effort` has `Low | Medium | High | XHigh | Max`.

## Conversations

The API is stateless. Send the whole history every time.

<!-- snip: Basics.scala#conversation -->
```scala
// A conversation is the request itself: append the reply, then the next user turn, and send it again.
def followUp(messages: Messages[IO], request: MessageRequest, question: String): IO[Message] =
  messages.create(request).flatMap { first =>
    messages.create(request.addMessages(first.toParam, MessageParam.user(question)))
  }
```

`Message.toParam` turns a reply into the assistant turn of the next request. Thinking blocks and unknown blocks
are kept, which the API needs for multi-turn tool use.

## Reading a reply

`reply.text` joins the text blocks, `reply.toolUses` lists the tool calls and `reply.usage` holds the token
counts (it has a `Show`). Always look at the stop reason:

<!-- snip: Basics.scala#stop-reasons -->
```scala
def describe(reply: Message): String =
  reply.stopReason match {
    case Some(StopReason.EndTurn)   => reply.text
    case Some(StopReason.MaxTokens) => s"${reply.text} [cut off: raise maxTokens or stream]"
    case Some(StopReason.Refusal)   => "The model declined."
    case Some(StopReason.ToolUse)   => s"Wants tools: ${reply.toolUses.map(_.name).mkString(", ")}"
    case other                      => s"Stopped: ${other.fold("unknown")(_.value)}"
  }
```

## Large outputs

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

## Prompt caching

<!-- snip: Basics.scala#caching -->
```scala
// A long, stable prefix is worth caching: the system prompt ends with a cache breakpoint.
def withPolicy(policy: String, question: String): MessageRequest =
  MessageRequest(Model.ClaudeOpus5_5, 16000, MessageParam.user(question))
    .withSystem(SystemPrompt.cached(policy))
```

`CacheControl.ephemeral.withTtl(CacheTtl.OneHour)` sets a longer TTL. Content blocks and tool definitions also
accept `withCacheControl`. Caching pays off only for a prefix that stays byte-identical between calls, so put
anything that changes after it.

## Counting tokens

<!-- snip: Basics.scala#count-tokens -->
```scala
def inputSize(messages: Messages[IO], request: MessageRequest): IO[Long] =
  messages.countTokens(request).map(_.inputTokens)
```

## Open enums and unmodeled fields

<!-- snip: Basics.scala#open-enums -->
```scala
// Enums are open: a model id from configuration, or one newer than this SDK version, still works.
val configured: Model = Model.of(sys.env.getOrElse("CLAUDE_MODEL", Model.ClaudeOpus5_5.value))
// Fields the SDK does not model yet go through withExtra.
val extra: MessageRequest = request.withExtra("metadata", io.circe.Json.obj())
```
