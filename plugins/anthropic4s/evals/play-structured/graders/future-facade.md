---
type: regex
target: { source: file, path: app/services/Claude.scala }
pattern: 'FutureAnthropic\.(fromEnv|start)[\s\S]*addStopHook|addStopHook[\s\S]*FutureAnthropic\.(fromEnv|start)'
---
