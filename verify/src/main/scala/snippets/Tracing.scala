package snippets

import cats.effect.{IO, IOApp}

// snip:start tracing
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
// snip:end
