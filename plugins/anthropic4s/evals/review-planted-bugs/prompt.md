---
description: Finds the planted misuse in an anthropic4s agent
tags: [review, agent]
max_turns: 20
allowed_tools: [Read, Glob, Grep, Skill, Agent]
---

Review this anthropic4s code a colleague wrote, and list what is wrong and how to fix each issue. Reply in the
chat; do not write files.

```scala
import anthropic4s._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.syntax._

final case class Delete(path: String)

class FileBot {
  def ask(question: String): String = {
    Anthropic4s.fromEnv[IO].use { client =>
      val request = MessageRequest(Model.of("claude-opus-5-5"), 64000, MessageParam.user(question))
        .withExtra("temperature", 0.2.asJson)
      val deleteTool = Tool("delete_file", "Deletes a file") { (in: Delete) =>
        IO(new java.io.File(in.path).delete()).as(s"deleted ${in.path}")
      }
      client.messages.create(request.addTools(deleteTool.definition)).flatMap { reply =>
        if (reply.stopReason.contains(StopReason.ToolUse))
          deleteTool.run(reply.toolUses.head).flatMap(r =>
            client.messages.create(request.addMessages(reply.toParam, MessageParam.user(List(r))))).map(_.text)
        else IO.pure(reply.text)
      }
    }.unsafeRunSync()
  }
}
```
