// PostToolUse (Write|Edit|MultiEdit): checks the file that was just written against rules.mjs and, when something
// looks wrong, puts the findings next to the tool result for Claude. Never blocks and never fails the tool call.
import fs from 'node:fs'
import { check } from './rules.mjs'

const MAX_BYTES = 512 * 1024

try {
  const input = JSON.parse(fs.readFileSync(0, 'utf8'))
  const file = input?.tool_input?.file_path
  if (typeof file === 'string' && fs.existsSync(file) && fs.statSync(file).size <= MAX_BYTES) {
    const warnings = check(file, fs.readFileSync(file, 'utf8'))
    if (warnings.length) {
      const additionalContext =
        `anthropic4s plugin check of ${file}:\n` + warnings.map((w) => `- ${w}`).join('\n')
      process.stdout.write(JSON.stringify({ hookSpecificOutput: { hookEventName: 'PostToolUse', additionalContext } }))
    }
  }
} catch {
  // A hook that cannot parse its input says nothing rather than getting in the way.
}
process.exit(0)
