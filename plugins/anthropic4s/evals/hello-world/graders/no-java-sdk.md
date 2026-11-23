---
type: regex
target: { source: file, path: Hello.scala }
pattern: 'com\.anthropic|AnthropicOkHttpClient|MessageCreateParams|temperature'
match: not_contains
---
