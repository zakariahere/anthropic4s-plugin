// SessionStart: when the project's build depends on anthropic4s, says so up front, so that Claude reaches for the
// anthropic4s skill instead of recalling the official Java SDK. Silent in every other project.
import fs from 'node:fs'
import path from 'node:path'
import { detect } from './rules.mjs'

try {
  const input = JSON.parse(fs.readFileSync(0, 'utf8') || '{}')
  const root = typeof input.cwd === 'string' ? input.cwd : process.cwd()
  const read = (p) => {
    try {
      return fs.statSync(p).size < 256 * 1024 ? fs.readFileSync(p, 'utf8') : ''
    } catch {
      return ''
    }
  }
  const names = [
    ...safeList(root).filter((n) => n.endsWith('.sbt')),
    ...safeList(path.join(root, 'project')).filter((n) => n.endsWith('.scala') || n.endsWith('.sbt')).map((n) => path.join('project', n)),
  ]
  const found = detect(names.map((n) => [n, read(path.join(root, n))]))
  if (found) {
    const version = found.version ? ` ${found.version}` : ''
    const additionalContext =
      `This project depends on anthropic4s${version} (${found.file}), an unofficial Scala SDK for the Claude API. ` +
      'Its API differs from the official Java SDK; the anthropic4s skill documents it with compiled examples, and ' +
      'the anthropic4s-reviewer agent checks code that uses it.'
    process.stdout.write(JSON.stringify({ hookSpecificOutput: { hookEventName: 'SessionStart', additionalContext } }))
  }
} catch {
  // Never get in the way of a session starting.
}
process.exit(0)

function safeList(dir) {
  try {
    return fs.readdirSync(dir)
  } catch {
    return []
  }
}
