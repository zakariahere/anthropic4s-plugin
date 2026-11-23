package snippets

import anthropic4s._
import cats.effect.IO

// snip:start structured-types
import anthropic4s.schema.{description, Schema}

sealed trait Difficulty
object Difficulty {
  case object Easy   extends Difficulty
  case object Medium extends Difficulty
  case object Hard   extends Difficulty
}

final case class Ingredient(name: String, @description("Amount with its unit, e.g. '200 g'") quantity: String)

final case class Recipe(
    name: String,
    @description("Total time in minutes, preparation included") minutes: Int,
    difficulty: Difficulty,
    ingredients: List[Ingredient],
    @description("A wine that goes with it, if any") pairing: Option[String]
)

object Recipe {
  // Optional: derive the schema once instead of at every call site.
  implicit val schema: Schema[Recipe] = Schema.derived
}
// snip:end

object Structured {

  // snip:start structured-parse
  def recipe(messages: Messages[IO]): IO[Recipe] = {
    val request = MessageRequest(Model.ClaudeOpus5_5, 16000, MessageParam.user("A weeknight vegetarian pasta."))
    messages
      .parse[Recipe](request) // the JSON Schema is derived from Recipe and sent as the output format
      .map(_.value)           // Parsed[Recipe] also carries the raw `message` (usage, stop reason)
  }

  // A refusal or a cut-off reply cannot be decoded: that is AnthropicError.OutputParse, with the reply.
  def recipeOrNone(messages: Messages[IO]): IO[Option[Recipe]] =
    recipe(messages).map(Option(_)).recover { case _: AnthropicError.OutputParse => None }
  // snip:end
}
