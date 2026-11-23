---
type: regex
target: { source: file, path: app/services/Claude.scala }
pattern: 'unsafeRun|Await\.result'
match: not_contains
---
