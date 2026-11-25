# anthropic4s plugin for Claude Code

A Claude Code plugin that teaches Claude to build Claude agents in **Scala** with
[anthropic4s](https://github.com/zakariahere/anthropic4s), the pure-Scala SDK for the Claude API (Cats Effect and fs2).

> **Unofficial.** anthropic4s and this plugin are community projects. They are not affiliated with or endorsed by
> Anthropic.

anthropic4s 1.1.0 shipped in November 2026, after current models were trained. Without help, a model writes
the official Java SDK's builders, or methods that do not exist. With this plugin installed, Claude knows the real
API, follows its rules, and can take an agent from an empty folder to a tested project.

## Install

In Claude Code:

```
/plugin marketplace add zakariahere/anthropic4s-plugin
/plugin install anthropic4s@anthropic4s-plugin
```

From a shell: `claude plugin marketplace add zakariahere/anthropic4s-plugin`, then
`claude plugin install anthropic4s@anthropic4s-plugin`.

## What is inside

| Component | What it does |
|---|---|
| Skill `anthropic4s` | Loads whenever Scala code talks to Claude. It holds thirteen rules that recall gets wrong, plus ten short references: requests, streaming, structured outputs, tools and agents, errors and configuration, testing, Play, tracing, batches, and decision models (`anthropic4s-decide`: classify, route and guard tools with a local System One model). |
| Skill `new-agent` (`/anthropic4s:new-agent <what it should do>`) | The end-to-end path: pin down the job and tools, scaffold an sbt project, write typed tools and the agent loop, test it with `ScriptedModel`, then optionally run it for real and trace it. |
| Agent `anthropic4s-reviewer` | A read-only review of anthropic4s code: client lifecycle, streaming limits, hand-rolled loops, ungated side effects, secrets, blocking, testability, and decision-model guards that fail open. |
| Hooks | `SessionStart` notes when the project depends on anthropic4s. After each edit, `PostToolUse` warns (never blocks) on API key literals, `temperature`/`top_p`/`top_k`, a model id that has a constant, `unsafeRunSync`, content tracing, large non-streaming requests, and mixing in the Java SDK. Node only, so they run on macOS, Linux and Windows. |
| Evals | Seven `claude plugin eval` cases: a first program, a support agent with a refund gate and tests, a long streamed report, Play with structured output, a ticket router on a decision model with Claude as the fallback, a review of planted bugs, and a check that the skill stays out of Python work. |

## Why you can trust the examples

Every Scala snippet in the skills is a region of a file in [`verify/`](verify). That sbt project depends on the
**published** anthropic4s from Maven Central, compiles with `-Xfatal-warnings`, and runs its testkit tests in CI.
`node scripts/snippets.mjs --check` fails CI when a Markdown block differs from its source region. The
`new-agent` template is a real sbt project that CI also tests. When anthropic4s releases a new version, bump
`anthropic4sVersion` in both builds, fix whatever breaks, and re-run `node scripts/snippets.mjs`.

## Developing

```
node --test test/hooks.test.mjs          # hook rules and scripts
node scripts/snippets.mjs                # copy verify/ regions into the skills' Markdown (--check: verify only)
cd verify && sbt test                    # snippets compile and pass against anthropic4s
claude plugin validate . && claude plugin validate ./plugins/anthropic4s
```

To try the plugin from a checkout without installing it: `claude --plugin-dir ./plugins/anthropic4s`.

### Evals

The cases write files, so grant `Write` and `Edit`. A full run is 7 cases × 3 runs × 2 arms (with and without
the plugin) and spends real tokens:

```
cd plugins/anthropic4s
claude plugin eval . --allow-tools Write Edit
claude plugin eval . --allow-tools Write Edit --case support-agent --runs 1 --ablation none   # one cheap run
```

The graders check the files the agent wrote (regex), whether the skill fired (`tool_used`), and a rubric per case
(`llm`). They do not compile the generated code: the eval sandbox has no sbt or network. That is what `verify/`
is for.

## License

Apache 2.0. See [LICENSE](LICENSE).
