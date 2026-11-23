// Cheap, high-precision checks on a file Claude just wrote. They warn and never block: a false alarm costs one
// sentence of context, and a missed one costs nothing that the reviewer agent will not catch later.

// Model constants in anthropic4s 1.0.0 (Model.scala), id -> constant.
export const MODEL_CONSTANTS = {
  'claude-fable-5-2': 'Model.ClaudeFable5_2',
  'claude-opus-5-5': 'Model.ClaudeOpus5_5',
  'claude-opus-5': 'Model.ClaudeOpus5',
  'claude-opus-4-8': 'Model.ClaudeOpus4_8',
  'claude-sonnet-5-5': 'Model.ClaudeSonnet5_5',
  'claude-sonnet-5': 'Model.ClaudeSonnet5',
  'claude-haiku-5-5': 'Model.ClaudeHaiku5_5',
  'claude-haiku-4-5': 'Model.ClaudeHaiku4_5',
}

// At the default 10-minute timeout, create() refuses maxTokens above 600 s * 128000 / 3600 s.
export const NON_STREAMING_LIMIT = 21333

const lineOf = (text, index) => text.slice(0, index).split('\n').length

const SCALA = /\.(scala|sc|sbt)$/i

export function check(filePath, text) {
  const warnings = []
  const at = (index, message) => warnings.push(`line ${lineOf(text, index)}: ${message}`)
  const base = filePath.split(/[\\/]/).pop() ?? ''

  // A real-looking key, in any file except the local env files that exist to hold one.
  if (!base.startsWith('.env')) {
    const key = /sk-ant-[A-Za-z0-9_-]{16,}/.exec(text)
    if (key) at(key.index, 'an Anthropic API key literal. Read it from ANTHROPIC_API_KEY (AnthropicConfig.fromEnv) or configuration, and rotate this key if it was ever committed.')
  }

  if (!SCALA.test(filePath) || !text.includes('anthropic4s')) return warnings

  if (/\.sbt$/i.test(filePath) || /[\\/]project[\\/][^\\/]+\.scala$/i.test(filePath)) {
    const old = /"anthropic4s[\w-]*"\s*%\s*"(0\.[^"]*)"/.exec(text)
    if (old) at(old.index, `anthropic4s ${old[1]} is a pre-release; 1.0.0 is on Maven Central and is what the anthropic4s skill documents.`)
    const scala3 = /scalaVersion\s*:=\s*"3\./.exec(text)
    if (scala3) at(scala3.index, 'anthropic4s 1.0.0 is published for Scala 2.13 only (Scala 3 is not supported yet).')
    return warnings
  }

  const javaSdk = /^\s*import\s+com\.anthropic\b/m.exec(text)
  if (javaSdk) at(javaSdk.index, 'mixes the official Java SDK (com.anthropic) with anthropic4s. anthropic4s covers the same API in Scala; use one SDK.')

  const sampling = /withExtra\(\s*"(temperature|top_p|top_k)"/.exec(text)
  if (sampling) at(sampling.index, `"${sampling[1]}" is rejected by the current API version, which is why anthropic4s does not model it. Steer with withThinking/withEffort and the prompt.`)

  for (const m of text.matchAll(/Model\.of\(\s*"([^"]+)"\s*\)/g)) {
    const constant = MODEL_CONSTANTS[m[1]]
    if (constant) at(m.index, `Model.of("${m[1]}") has a constant: ${constant}.`)
    else if (m[1].startsWith('claude-')) at(m.index, `"${m[1]}" is not one of the model constants in anthropic4s 1.0.0 (${Object.values(MODEL_CONSTANTS).slice(0, 4).join(', ')}, ...). Make sure the id exists.`)
  }

  const unsafe = /\.unsafeRun(Sync|AndForget)\s*\(/.exec(text)
  if (unsafe) at(unsafe.index, `unsafeRun${unsafe[1]} leaves IO. Compose effects (IOApp, Resource.use), or use FutureAnthropic from Future code.`)

  const content = /ContentMode\.Content\b/.exec(text)
  if (content) at(content.index, 'ContentMode.Content records prompts and replies in traces. Keep it to demos; real user data needs ContentMode.MetadataOnly (the default).')

  const streams = /\.stream\(|toolRunner|runTools|streamText|textDeltas|finalMessage/.test(text)
  if (/\.create\(/.test(text) && !streams) {
    for (const m of text.matchAll(/(?:maxTokens\s*=\s*|MessageRequest\(\s*[\w.]+\s*,\s*)(\d[\d_]*)/g)) {
      const n = Number(m[1].replace(/_/g, ''))
      if (n > NON_STREAMING_LIMIT) {
        at(m.index, `maxTokens ${n} with a non-streaming create(): at the default 10-minute timeout anything above ~${NON_STREAMING_LIMIT} fails with AnthropicError.StreamingRequired. Stream (messages.stream(...).finalMessage) or lower it.`)
        break
      }
    }
  }

  return warnings
}

// The Scala build files of a project root that mention anthropic4s, and the version they pin, if any.
export function detect(files) {
  for (const [name, text] of files) {
    if (!text.includes('anthropic4s')) continue
    const version = /"anthropic4s[\w-]*"\s*%\s*"([^"]+)"/.exec(text) ?? /anthropic4s[\w-]*Version\s*=\s*"([^"]+)"/.exec(text)
    return { file: name, version: version?.[1] }
  }
  return null
}
