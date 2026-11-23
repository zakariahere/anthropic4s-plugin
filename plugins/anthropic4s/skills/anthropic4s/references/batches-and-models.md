# Batches, models and betas

## Message Batches

Batches cost half price and return within 24 hours. Use them for bulk work that nobody waits on.

<!-- snip: Advanced.scala#batches -->
```scala
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
```

`client.batches` also has `retrieve`, `list`/`listAll`, `cancel` and `delete`. A `BatchResult`'s `outcome` is
`BatchOutcome.Succeeded(message)`, `Errored(error)` or another state for canceled or expired requests.

## Models

<!-- snip: Advanced.scala#models -->
```scala
def available(client: AnthropicClient[IO]): IO[List[Model]] =
  client.models.listAll.map(_.id).compile.toList
```

`client.models.retrieve(Model.ClaudeOpus5_5)` returns a `ModelInfo`.

## Files

`client.files` has `upload(filename, mimeType, bytes: Stream[F, Byte])`, `retrieveMetadata`, `list`/`listAll`,
`download` and `delete`.

## Betas

<!-- snip: Advanced.scala#betas -->
```scala
// Beta features are flags on the same types, per client (AnthropicConfig.withBetas) or per call:
def withBeta(messages: Messages[IO], request: MessageRequest): IO[Message] =
  messages.create(request, RequestOptions.default.withBetas(Beta.TaskBudgets))
```
