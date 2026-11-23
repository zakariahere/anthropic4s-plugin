package agent

import anthropic4s._
import cats.effect.{ExitCode, IO, IOApp}

/** `sbt "run What time is it in Tokyo?"`. Needs ANTHROPIC_API_KEY and spends real tokens. */
object Main extends IOApp {

  def run(args: List[String]): IO[ExitCode] = {
    val question = if (args.nonEmpty) args.mkString(" ") else "What time is it in Brussels?"
    // One client for the whole program: a Resource, closed when `use` ends.
    Anthropic4s.fromEnv[IO].use { client =>
      new Agent(client.messages, Tools.all).ask(question).flatMap(IO.println)
    }.as(ExitCode.Success)
  }
}
