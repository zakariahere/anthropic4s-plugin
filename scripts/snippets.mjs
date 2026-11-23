// Keeps the Scala code in the plugin's Markdown identical to verify/, which CI compiles and tests against the
// published anthropic4s. A skill that teaches a signature the library does not have is worse than no skill.
//
// In Markdown, a marker line names the region; the fenced block right after it is generated:
//
//   <!-- snip: Agents.scala#tools -->
//   ```scala
//   ...generated, never edited by hand...
//   ```
//
// In verify/src/**/Agents.scala the region is delimited by `// snip:start tools` and `// snip:end`.
//
//   node scripts/snippets.mjs           rewrite the blocks
//   node scripts/snippets.mjs --check   change nothing; exit 1 when a block is stale or a region is missing
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..')
const check = process.argv.includes('--check')
const lf = (s) => s.replace(/\r\n/g, '\n')

const walk = (dir, keep) =>
  fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) return ['target', 'node_modules', '.git', 'results'].includes(e.name) ? [] : walk(p, keep)
    return keep(p) ? [p] : []
  })

// Every region of every Scala file under verify/src, keyed "File.scala#name".
const regions = new Map()
for (const file of walk(path.join(root, 'verify', 'src'), (p) => p.endsWith('.scala'))) {
  const lines = lf(fs.readFileSync(file, 'utf8')).split('\n')
  let open = null
  lines.forEach((line, i) => {
    const start = line.match(/^\s*\/\/ snip:start (\S+)\s*$/)
    if (start) {
      if (open) throw new Error(`${file}:${i + 1}: region ${start[1]} opens inside ${open.name}`)
      open = { name: start[1], from: i + 1 }
    } else if (/^\s*\/\/ snip:end\s*$/.test(line)) {
      if (!open) throw new Error(`${file}:${i + 1}: snip:end without a start`)
      const key = `${path.basename(file)}#${open.name}`
      if (regions.has(key)) throw new Error(`duplicate region ${key}`)
      regions.set(key, dedent(lines.slice(open.from, i)))
      open = null
    }
  })
  if (open) throw new Error(`${file}: region ${open.name} is never closed`)
}

function dedent(lines) {
  const indents = lines.filter((l) => l.trim()).map((l) => l.match(/^ */)[0].length)
  const cut = indents.length ? Math.min(...indents) : 0
  return lines.map((l) => l.slice(cut)).join('\n').replace(/\n+$/, '')
}

const docs = walk(path.join(root, 'plugins'), (p) => p.endsWith('.md'))
const used = new Set()
let stale = 0
for (const doc of docs) {
  const before = lf(fs.readFileSync(doc, 'utf8'))
  // The body is whole lines up to the first line that starts with ``` (an empty block has no body lines), so a
  // block can never reach past its own closing fence.
  const after = before.replace(
    /(<!-- snip: (\S+?) -->\n```scala\n)((?:[^\n]*\n)*?)(```)(?=\n|$)/g,
    (_, head, key, _body, tail) => {
      const code = regions.get(key)
      if (code === undefined) throw new Error(`${path.relative(root, doc)}: no region ${key} in verify/src`)
      used.add(key)
      return head + code + '\n' + tail
    }
  )
  // A marker whose next line is not a ```scala fence would be silently skipped: refuse it.
  const markers = [...before.matchAll(/<!-- snip: (\S+?) -->\n(?!```scala\n)/g)]
  if (markers.length) throw new Error(`${path.relative(root, doc)}: marker ${markers[0][1]} is not followed by \`\`\`scala`)
  if (after !== before) {
    stale++
    if (check) console.error(`stale: ${path.relative(root, doc)}`)
    else fs.writeFileSync(doc, after)
  }
}

const unused = [...regions.keys()].filter((k) => !used.has(k))
console.log(`${regions.size} regions, ${used.size} quoted in ${docs.length} Markdown files` +
  (unused.length ? `; not quoted: ${unused.join(', ')}` : ''))
if (check && stale) {
  console.error('Run `node scripts/snippets.mjs` and commit the result.')
  process.exit(1)
}
