# Structured outputs

Ask for a type and get a value of it back. The JSON Schema is derived from the type and sent as the output
format, so the API constrains the reply to fit it.

<!-- snip: Structured.scala#structured-types -->
```scala
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
```

<!-- snip: Structured.scala#structured-parse -->
```scala
def recipe(messages: Messages[IO]): IO[Recipe] = {
  val request = MessageRequest(Model.ClaudeOpus5_5, 16000, MessageParam.user("A weeknight vegetarian pasta."))
  messages
    .parse[Recipe](request) // the JSON Schema is derived from Recipe and sent as the output format
    .map(_.value)           // Parsed[Recipe] also carries the raw `message` (usage, stop reason)
}

// A refusal or a cut-off reply cannot be decoded: that is AnthropicError.OutputParse, with the reply.
def recipeOrNone(messages: Messages[IO]): IO[Option[Recipe]] =
  recipe(messages).map(Option(_)).recover { case _: AnthropicError.OutputParse => None }
```

## How types map to the schema

- A case class becomes an object with every field required. `Option[A]` is the way to make a field optional
  (the model sends `null`).
- A sealed trait of case objects becomes a string enum.
- A sealed trait of case classes becomes `anyOf`, tagged by a `type` field.
- `List`, `Vector`, `Set`, `Map[String, A]`, numbers, `Boolean` and `String` map as you would expect.
- `@description("...")` (from `anthropic4s.schema`) on a field or type is shown to the model. Use it for units,
  formats and examples.
- The root of the schema must be an object (a case class), not a bare `String` or `List`.

There is no instance to declare: `Schema[A]` is derived automatically. To derive it once, cache it in the
companion: `implicit val schema: Schema[A] = Schema.derived`.

## With Play JSON

Add `anthropic4s-play-json`, and give the type a `Format` that agrees with the schema:

<!-- snip: PlaySnippets.scala#play-structured -->
```scala
final case class Ticket(@description("One-line summary") title: String, urgent: Boolean, assignee: Option[String])

object Ticket {
  implicit val config: JsonConfiguration = PlaySchema.configuration // nulls written, `type` tags: the schema's shape
  implicit val format: OFormat[Ticket]   = Json.format[Ticket]
  implicit val schema: Schema[Ticket]    = PlaySchema.derived[Ticket]
}
```

- `PlaySchema.configuration` makes `Json.format` write a missing `Option` as `null` and tag sealed traits with
  `type`. That is the shape the schema promises.
- For a sealed trait of case objects, use `PlaySchema.enumFormat("Low" -> Low, "High" -> High)`.
- `Json.format` cannot see the cases of a sealed trait declared inside the same companion that calls it, so
  declare the cases at the top level.

## Testing it

<!-- snip: TravelAgentSuite.scala#testing-structured -->
```scala
test("structured output, scripted") {
  for {
    model  <- ScriptedModel.of[IO](Replies.structured(Recipe("Pasta al limone", 20, Difficulty.Easy, Nil, None)))
    recipe <- Structured.recipe(model)
  } yield assertEquals(recipe.minutes, 20)
}
```
