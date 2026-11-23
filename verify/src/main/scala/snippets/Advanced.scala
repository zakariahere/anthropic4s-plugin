package snippets

import anthropic4s._
import cats.data.NonEmptyList
import cats.effect.IO

object Advanced {

  // snip:start batches
  // Batches: half price, results within 24 hours, in any order. Match them by customId.
  def classifyAll(client: AnthropicClient[IO], reviews: NonEmptyList[(String, String)]): IO[Map[String, String]] = {
    val requests = reviews.map { case (id, text) =>
      BatchRequest(id, MessageRequest(Model.ClaudeHaiku5_5, 256, MessageParam.user(s"Positive or negative? $text")))
    }
    for {
      batch   <- client.batches.create(requests)
      _       <- client.batches.awaitEnded(batch.id) // polls every 30 seconds
      results <- client.batches.results(batch.id).compile.toList
    } yield results.collect { case BatchResult(id, BatchOutcome.Succeeded(message)) => id -> message.text }.toMap
  }
  // snip:end

  // snip:start models
  def available(client: AnthropicClient[IO]): IO[List[Model]] =
    client.models.listAll.map(_.id).compile.toList
  // snip:end

  // snip:start betas
  // Beta features are flags on the same types, per client (AnthropicConfig.withBetas) or per call:
  def withBeta(messages: Messages[IO], request: MessageRequest): IO[Message] =
    messages.create(request, RequestOptions.default.withBetas(Beta.TaskBudgets))
  // snip:end
}
