package agent

import anthropic4s._
import anthropic4s.schema.description
import cats.effect.IO

import java.time.{ZoneId, ZonedDateTime}
import scala.util.Try

/** Arguments of a tool: a case class. Every field is required unless it is an Option. */
final case class CurrentTime(@description("IANA time zone, e.g. Europe/Brussels") zone: String)

/** The result goes back to the model as JSON. */
final case class TimeOfDay(zone: String, iso: String)

/** The tools this agent can call. Replace the example with the real ones; keep each a small, typed function. */
object Tools {

  val currentTime: Tool[IO] =
    Tool("current_time", "The current date and time in a time zone") { (in: CurrentTime) =>
      IO.fromTry(Try(ZoneId.of(in.zone))) // a bad zone fails the call; the model sees the error and can retry
        .map(zone => TimeOfDay(in.zone, ZonedDateTime.now(zone).toString))
    }

  val all: List[Tool[IO]] = List(currentTime)
}
