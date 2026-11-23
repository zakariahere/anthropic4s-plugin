package snippets

// snip:start imports
import anthropic4s._
import cats.effect.{IO, IOApp}
import cats.syntax.all._
// snip:end

object Hello extends IOApp.Simple {

  // snip:start hello
  def run: IO[Unit] =
    Anthropic4s.fromEnv[IO].use { client => // reads ANTHROPIC_API_KEY; closes its connections afterwards
      val request = MessageRequest(Model.ClaudeOpus5_5, maxTokens = 16000, MessageParam.user("Why do cats purr?"))

      client.messages.create(request).flatMap { reply =>
        // Look at how the reply ended before trusting its content.
        if (reply.isRefusal) IO.println("The model declined.")
        else IO.println(reply.text) *> IO.println(reply.usage.show)
      }
    }
  // snip:end
}

object Requests {

  // snip:start request-builders
  val request: MessageRequest =
    MessageRequest(Model.ClaudeOpus5_5, maxTokens = 16000, MessageParam.user("Summarise this contract: ..."))
      .withSystem("You are a careful paralegal. Answer in plain English.")
      .withThinking(ThinkingConfig.adaptive) // the model decides how much to think
      .withEffort(Effort.High)
      .withStopSequences(List("</answer>"))
  // snip:end

  // snip:start conversation
  // A conversation is the request itself: append the reply, then the next user turn, and send it again.
  def followUp(messages: Messages[IO], request: MessageRequest, question: String): IO[Message] =
    messages.create(request).flatMap { first =>
      messages.create(request.addMessages(first.toParam, MessageParam.user(question)))
    }
  // snip:end

  // snip:start stop-reasons
  def describe(reply: Message): String =
    reply.stopReason match {
      case Some(StopReason.EndTurn)   => reply.text
      case Some(StopReason.MaxTokens) => s"${reply.text} [cut off: raise maxTokens or stream]"
      case Some(StopReason.Refusal)   => "The model declined."
      case Some(StopReason.ToolUse)   => s"Wants tools: ${reply.toolUses.map(_.name).mkString(", ")}"
      case other                      => s"Stopped: ${other.fold("unknown")(_.value)}"
    }
  // snip:end

  // snip:start caching
  // A long, stable prefix is worth caching: the system prompt ends with a cache breakpoint.
  def withPolicy(policy: String, question: String): MessageRequest =
    MessageRequest(Model.ClaudeOpus5_5, 16000, MessageParam.user(question))
      .withSystem(SystemPrompt.cached(policy))
  // snip:end

  // snip:start count-tokens
  def inputSize(messages: Messages[IO], request: MessageRequest): IO[Long] =
    messages.countTokens(request).map(_.inputTokens)
  // snip:end

  // snip:start open-enums
  // Enums are open: a model id from configuration, or one newer than this SDK version, still works.
  val configured: Model = Model.of(sys.env.getOrElse("CLAUDE_MODEL", Model.ClaudeOpus5_5.value))
  // Fields the SDK does not model yet go through withExtra.
  val extra: MessageRequest = request.withExtra("metadata", io.circe.Json.obj())
  // snip:end
}
