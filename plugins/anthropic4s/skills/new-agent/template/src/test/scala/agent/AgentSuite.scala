package agent

import anthropic4s._
import anthropic4s.testkit.{Replies, ScriptedModel}
import cats.effect.IO
import io.circe.Json
import io.circe.syntax._
import munit.CatsEffectSuite

/** No network and no API key: the scripted model plays Claude's part. */
class AgentSuite extends CatsEffectSuite {

  test("calls the tool, then answers") {
    for {
      model <- ScriptedModel.of[IO](
        Replies.toolUse("current_time", Json.obj("zone" -> "Asia/Tokyo".asJson)),
        Replies.text("It is evening in Tokyo.")
      )
      answer <- new Agent(model, Tools.all).ask("What time is it in Tokyo?")
      sent   <- model.requests
    } yield {
      assertEquals(answer, "It is evening in Tokyo.")
      assertEquals(sent.size, 2)
      val toolResult = sent(1).messages.last.content.blocks.collectFirst { case r: ContentBlock.ToolResult => r }
      assert(toolResult.exists(r => !r.isError && r.text.contains("Asia/Tokyo")), toolResult)
    }
  }

  test("a bad argument comes back to the model as an error result") {
    for {
      model <- ScriptedModel.of[IO](
        Replies.toolUse("current_time", Json.obj("zone" -> "Mars/Olympus".asJson)),
        Replies.text("That is not a time zone.")
      )
      _    <- new Agent(model, Tools.all).ask("Time on Mars?")
      sent <- model.requests
    } yield {
      val toolResult = sent(1).messages.last.content.blocks.collectFirst { case r: ContentBlock.ToolResult => r }
      assert(toolResult.exists(_.isError), toolResult)
    }
  }

  test("a refusal is an answer, not an exception") {
    for {
      model  <- ScriptedModel.of[IO](Replies.stopped(StopReason.Refusal))
      answer <- new Agent(model, Tools.all).ask("...")
    } yield assertEquals(answer, "I can't help with that.")
  }
}
