package agent

import anthropic4s._
import cats.effect.IO

/**
 * The agent: a system prompt, a set of tools and the tool runner's loop.
 *
 * It takes `Messages[IO]` rather than a client, so tests can pass a `ScriptedModel` and run without a network.
 */
final class Agent(messages: Messages[IO], tools: List[Tool[IO]]) {

  private val system =
    """You are a helpful assistant. Use the tools when they give a better answer than memory.
      |Answer briefly.""".stripMargin

  private val config: RunnerConfig[IO] =
    RunnerConfig
      .default[IO]
      .withMaxIterations(10)
      // An approval gate for tools with side effects; everything is allowed in this template.
      .withBeforeTool(_ => IO.pure(ToolDecision.Allow))

  def ask(question: String): IO[String] = {
    val request =
      MessageRequest(Model.ClaudeOpus5_5, maxTokens = 64000, MessageParam.user(question)) // the runner streams
        .withSystem(system)
        .withThinking(ThinkingConfig.adaptive)

    messages.toolRunner(tools, config).run(request).map { result =>
      result.stop match {
        case RunStop.Completed       => result.text
        case RunStop.Refused(_)      => "I can't help with that."
        case RunStop.MaxTokens       => s"${result.text}\n(cut off)"
        case RunStop.MaxIterations   => "That took too many steps."
        case RunStop.Aborted(reason) => s"Stopped: $reason"
        case RunStop.Other(reason)   => s"Stopped: ${reason.value}"
      }
    }
  }
}
