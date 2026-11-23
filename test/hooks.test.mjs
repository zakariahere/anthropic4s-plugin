// node --test test/
import test from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { check, detect, MODEL_CONSTANTS } from '../plugins/anthropic4s/hooks/rules.mjs'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..')
const hooks = path.join(root, 'plugins', 'anthropic4s', 'hooks')
const uses = 'import anthropic4s._\n'

test('clean anthropic4s code passes', () => {
  const code = uses + 'val r = MessageRequest(Model.ClaudeOpus5_5, 16000, MessageParam.user("hi"))\nclient.messages.create(r)\n'
  assert.deepEqual(check('A.scala', code), [])
})

test('every verify snippet is clean', () => {
  const dir = path.join(root, 'verify', 'src')
  const files = fs.readdirSync(dir, { recursive: true }).filter((f) => f.endsWith('.scala'))
  assert.ok(files.length > 5)
  for (const f of files) {
    const warnings = check(f, fs.readFileSync(path.join(dir, f), 'utf8'))
    assert.deepEqual(warnings, [], f)
  }
})

test('the scaffold template is clean', () => {
  const dir = path.join(root, 'plugins', 'anthropic4s', 'skills', 'new-agent', 'template')
  for (const f of fs.readdirSync(dir, { recursive: true }).filter((f) => /\.(scala|sbt)$/.test(f))) {
    assert.deepEqual(check(f, fs.readFileSync(path.join(dir, f), 'utf8')), [], f)
  }
})

test('an API key literal is caught in any file, but not in .env', () => {
  const key = 'sk-ant-api03-' + 'x'.repeat(40)
  assert.match(check('application.conf', `key = "${key}"`)[0], /API key literal/)
  assert.match(check('README.md', key)[0], /line 1/)
  assert.deepEqual(check('.env', `ANTHROPIC_API_KEY=${key}`), [])
  assert.deepEqual(check('.env.local', `ANTHROPIC_API_KEY=${key}`), [])
})

test('sampling parameters through withExtra', () => {
  assert.match(check('A.scala', uses + 'r.withExtra("temperature", 0.2.asJson)').join(), /rejected/)
})

test('Model.of with a known id suggests the constant; an unknown claude id is flagged', () => {
  assert.match(check('A.scala', uses + 'Model.of("claude-opus-5-5")').join(), /Model\.ClaudeOpus5_5/)
  assert.match(check('A.scala', uses + 'Model.of("claude-3-opus-20240229")').join(), /not one of the model constants/)
  assert.deepEqual(check('A.scala', uses + 'Model.of("my-proxy-model")'), [])
})

test('the constants match the library', () => {
  assert.equal(Object.keys(MODEL_CONSTANTS).length, 8)
})

test('large maxTokens with create() and no streaming', () => {
  const big = uses + 'val r = MessageRequest(Model.ClaudeOpus5_5, maxTokens = 64_000, MessageParam.user("x"))\nclient.messages.create(r)'
  assert.match(check('A.scala', big).join(), /StreamingRequired/)
  assert.match(check('A.scala', uses + 'MessageRequest(Model.ClaudeOpus5_5, 32000, m)\nm.create(r)').join(), /StreamingRequired/)
  assert.deepEqual(check('A.scala', big + '\nclient.messages.stream(r)'), [])
  assert.deepEqual(check('A.scala', uses + 'MessageRequest(Model.ClaudeOpus5_5, 16000, m)\nm.create(r)'), [])
})

test('mixing the Java SDK, unsafeRunSync and content tracing', () => {
  assert.match(check('A.scala', 'import com.anthropic.client.AnthropicClient\n' + uses).join(), /Java SDK/)
  assert.match(check('A.scala', uses + 'io.unsafeRunSync()').join(), /leaves IO/)
  assert.match(check('A.scala', uses + 'OtelConfig.default.withContentMode(ContentMode.Content)').join(), /demos/)
  assert.deepEqual(check('A.scala', uses + 'ContentMode.MetadataOnly'), [])
})

test('files that do not use anthropic4s are left alone', () => {
  assert.deepEqual(check('A.scala', 'Model.of("claude-3-opus")\nio.unsafeRunSync()'), [])
  assert.deepEqual(check('A.ts', 'anthropic4s Model.of("claude-3")'), [])
})

test('build files: pre-release versions and Scala 3', () => {
  assert.match(check('build.sbt', '"io.github.zakariahere" %% "anthropic4s" % "0.9.0"').join(), /pre-release/)
  assert.match(check('build.sbt', 'scalaVersion := "3.3.6"\n"anthropic4s" % "1.0.0"').join(), /Scala 2\.13 only/)
  assert.deepEqual(check('build.sbt', 'scalaVersion := "2.13.18"\n"io.github.zakariahere" %% "anthropic4s" % "1.0.0"'), [])
})

test('detect finds the dependency and its version', () => {
  assert.deepEqual(detect([['build.sbt', '"io.github.zakariahere" %% "anthropic4s" % "1.0.0"']]), { file: 'build.sbt', version: '1.0.0' })
  assert.deepEqual(detect([['build.sbt', 'val anthropic4sVersion = "1.0.0"\n%% "anthropic4s" % anthropic4sVersion']]), { file: 'build.sbt', version: '1.0.0' })
  assert.equal(detect([['build.sbt', 'libraryDependencies += "org.typelevel" %% "cats-core" % "2.13.0"']]), null)
})

const run = (script, input) =>
  spawnSync(process.execPath, [path.join(hooks, script)], { input: JSON.stringify(input), encoding: 'utf8' })

test('check-scala.mjs emits additionalContext for a bad file and nothing for a clean one', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'a4s-'))
  const bad = path.join(dir, 'Bad.scala')
  fs.writeFileSync(bad, uses + 'Model.of("claude-opus-5-5")')
  const out = run('check-scala.mjs', { hook_event_name: 'PostToolUse', tool_name: 'Write', tool_input: { file_path: bad } })
  assert.equal(out.status, 0)
  const json = JSON.parse(out.stdout)
  assert.equal(json.hookSpecificOutput.hookEventName, 'PostToolUse')
  assert.match(json.hookSpecificOutput.additionalContext, /Model\.ClaudeOpus5_5/)

  const good = path.join(dir, 'Good.scala')
  fs.writeFileSync(good, uses + 'Model.ClaudeOpus5_5')
  const quiet = run('check-scala.mjs', { tool_input: { file_path: good } })
  assert.equal(quiet.status, 0)
  assert.equal(quiet.stdout, '')
})

test('check-scala.mjs survives garbage input and missing files', () => {
  const garbage = spawnSync(process.execPath, [path.join(hooks, 'check-scala.mjs')], { input: 'not json', encoding: 'utf8' })
  assert.equal(garbage.status, 0)
  assert.equal(run('check-scala.mjs', { tool_input: { file_path: '/no/such/file.scala' } }).status, 0)
})

test('session-start.mjs speaks up only in anthropic4s projects', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'a4s-'))
  assert.equal(run('session-start.mjs', { cwd: dir, source: 'startup' }).stdout, '')
  fs.writeFileSync(path.join(dir, 'build.sbt'), '"io.github.zakariahere" %% "anthropic4s" % "1.0.0"')
  const out = run('session-start.mjs', { cwd: dir, source: 'startup' })
  assert.equal(out.status, 0)
  assert.match(JSON.parse(out.stdout).hookSpecificOutput.additionalContext, /anthropic4s 1\.0\.0 \(build\.sbt\)/)
})
