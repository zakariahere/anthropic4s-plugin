---
type: regex
target: { source: file, path: src/main/scala/router/TicketRouter.scala }
pattern: '"[^"\n]*(v1/systemone|localhost:8081)[^"\n]*"'
match: not_contains
---
