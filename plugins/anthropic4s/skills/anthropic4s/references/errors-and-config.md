# Errors, retries and configuration

## Typed errors

Every failure is raised in `F` as a subtype of `AnthropicError`, so handling it is a pattern match:

<!-- snip: Errors.scala#errors -->
```scala
def answer(messages: Messages[IO], request: MessageRequest): IO[String] =
  messages.create(request).map(_.text).recover {
    case e: AnthropicError.RateLimited => s"Busy; retry in ${e.retryAfter.fold("a moment")(_.toString)}"
    case _: AnthropicError.Overloaded  => "Claude is overloaded; try again shortly."
    case e: AnthropicError.Api         => s"API error ${e.status} ${e.errorType}: ${e.errorMessage} (${e.requestId})"
    case _: AnthropicError.Timeout     => "Timed out."
    case _: AnthropicError.Connection  => "Could not reach the API."
  }
```

`AnthropicError.Api` is the parent of every HTTP error and carries `status`, `errorType`, `errorMessage`,
`requestId` and `body`. Its subtypes are `BadRequest` (400), `Authentication` (401), `Billing`,
`PermissionDenied` (403), `NotFound` (404), `Conflict` (409), `RequestTooLarge` (413), `UnprocessableEntity`,
`RateLimited` (429, with `retryAfter`), `Overloaded` (529), `Server` (5xx) and `UnexpectedStatus`.

The other errors are:

- `Connection` and `Timeout(after)`: the API could not be reached in time.
- `Decoding`, `StreamFailure` and `StreamTruncated`: the reply was broken.
- `StreamingRequired`: `create` with too large a `maxTokens`; stream instead.
- `InvalidSchema` and `OutputParse`: from tools and `parse`.
- `Configuration`: for example, no API key.

Match the specific errors before `Api`.

## Retries and timeouts

Connection errors, timeouts, 408, 409, 429 and 5xx are retried **twice** with exponential backoff, honouring
the `retry-after` and `x-should-retry` headers. A request is never retried once a response has started. Do not
add another retry layer on top.

<!-- snip: Errors.scala#config -->
```scala
// One client for the application, configured once.
def client(apiKey: String): Resource[IO, AnthropicClient[IO]] =
  Anthropic4s.resource[IO](
    AnthropicConfig(apiKey)
      .withMaxRetries(4)          // default 2, with exponential backoff and retry-after
      .withTimeout(2.minutes)     // default 10 minutes
      .withBetas(Beta.Compaction) // sent on every request
  )

// Or per call:
def quick(messages: Messages[IO], request: MessageRequest): IO[Message] =
  messages.create(request, RequestOptions.default.withTimeout(30.seconds).withMaxRetries(0))
```

`AnthropicConfig.fromEnv[IO]` reads `ANTHROPIC_API_KEY` (or `ANTHROPIC_AUTH_TOKEN`) and the optional
`ANTHROPIC_BASE_URL`. `AnthropicConfig` also has `withBaseUrl`, `withHeader`, `withRetryPolicy` and
`withApiVersion`. `RequestOptions` also has `withHeader`, `withQueryParam` and `withBetas`.

## Betas

Beta features are flags, not a parallel set of types. The constants are `Beta.Compaction`,
`ContextManagement`, `TaskBudgets`, `McpClient`, `MessageThreads`, `CacheDiagnosis` and `ServerSideFallback`,
or `Beta.of("...")`. Set them per client with `AnthropicConfig.withBetas`, or per call with
`RequestOptions.withBetas`.
