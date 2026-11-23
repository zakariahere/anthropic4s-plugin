# Tracing with OpenTelemetry

Add `anthropic4s-otel4s` (and `org.typelevel %% otel4s-oteljava` plus an exporter to run it), then pass a
telemetry to the client:

<!-- snip: Tracing.scala#tracing -->
```scala
import anthropic4s._
import anthropic4s.otel.{ContentMode, OtelConfig, OtelTelemetry}
import org.typelevel.otel4s.oteljava.OtelJava

object Traced extends IOApp.Simple {

  def run: IO[Unit] =
    OtelJava.autoConfigured[IO]().use { otel => // OTLP to localhost:4318 unless OTEL_* says otherwise
      for {
        // MetadataOnly (the default) records model, usage, stop reason and ids. Content also records prompts
        // and replies: only for demos, never for real user data.
        telemetry <- OtelTelemetry[IO](otel.tracerProvider, OtelConfig.default.withContentMode(ContentMode.MetadataOnly))
        config    <- AnthropicConfig.fromEnv[IO]
        _         <- Anthropic4s.resource[IO](config, telemetry).use { client =>
          // An agent run becomes one trace: a span for the run, one per request, one per tool call.
          client.messages.create(MessageRequest(Model.ClaudeOpus5_5, 1024, MessageParam.user("Hi"))).void
        }
      } yield ()
    }
}
```

- Each API request is a `CLIENT` span (retries included). An agent run is one tree: a span for the run, one per
  request and one per tool call.
- Spans carry the model, token usage, stop reason and request ids as `gen_ai.*` and `anthropic.*` attributes.
- `ContentMode.MetadataOnly` (the default) records no prompts or replies. `ContentMode.Content` does, capped by
  `withMaxContentBytes`. Use it only where the data may be stored.
- `OtelConfig.withPropagation(false)` stops trace headers from going to the API.
- With no telemetry passed, tracing costs nothing.
