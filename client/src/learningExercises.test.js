import test from 'node:test'
import assert from 'node:assert/strict'
import { createLearningExercises } from './learningExercises.js'

globalThis.localStorage = { getItem: () => 'session-a' }
const ok = data => ({ ok: true, json: async () => data })
const question = { id: 'q1', segmentId: 's1', timestampMs: 125000, prompt: '____ trees' }

test('wrong answer displays persisted knowledge and original timestamp, not browser grading', async () => {
  const calls = []
  const result = { id: 5, exerciseId: 'q1', correct: false, knowledgePoint: '二叉树', segmentId: 's1', timestampMs: 125000 }
  const workspace = createLearningExercises({ mediaId: 1, goal: 'goal', uuid: () => 'request-id', request: async (url, options) => {
    calls.push([url, options])
    return ok(result)
  } })
  workspace.state.answers.q1 = 'wrong'
  await workspace.submit(question)
  assert.equal(workspace.state.attempts[0].knowledgePoint, '二叉树')
  assert.equal(workspace.state.attempts[0].timestampMs, 125000)
  assert.deepEqual(JSON.parse(calls[0][1].body), { answer: 'wrong', requestId: 'request-id' })
})

test('lost answer response retains idempotency receipt on retry and never inserts a local fake record', async () => {
  const receipts = []
  let sequence = 0
  const workspace = createLearningExercises({ mediaId: 1, goal: 'goal', uuid: () => `id-${++sequence}`, request: async (_, options) => {
    receipts.push(JSON.parse(options.body))
    if (receipts.length === 1) throw new Error('网络中断')
    return ok({ id: 8, correct: false })
  } })
  workspace.state.answers.q1 = 'wrong'
  await workspace.submit(question)
  assert.equal(workspace.state.attempts.length, 0)
  assert.equal(workspace.state.error, '网络中断')
  await workspace.submit(question)
  assert.deepEqual(receipts[0], receipts[1])
  assert.equal(workspace.state.attempts.length, 1)
})

test('reopening restores questions and history from server; wrong-only pagination uses cursor', async () => {
  const urls = []
  const workspace = createLearningExercises({ mediaId: 1, goal: 'goal', request: async url => {
    urls.push(url)
    return ok(url.includes('/exercises?') ? [question] : [{ id: 9, correct: false }])
  } })
  await workspace.load()
  assert.deepEqual([...workspace.state.exercises], [question])
  assert.equal(workspace.state.attempts[0].id, 9)
  workspace.state.wrongOnly = true
  await workspace.history(true)
  assert.match(urls.at(-1), /wrongOnly=true&beforeId=9/)
})

test('disposed workspace and changed auth session discard late responses', async () => {
  let resolve
  const workspace = createLearningExercises({ mediaId: 1, goal: 'goal', request: () => new Promise(r => { resolve = r }) })
  const pending = workspace.generate()
  workspace.stop()
  resolve(ok([question]))
  await pending
  assert.equal(workspace.state.exercises.length, 0)
  const other = createLearningExercises({ mediaId: 1, goal: 'goal', request: () => new Promise(r => { resolve = r }) })
  const second = other.generate()
  globalThis.localStorage = { getItem: () => 'session-b' }
  resolve(ok([question]))
  await second
  assert.equal(other.state.exercises.length, 0)
  globalThis.localStorage = { getItem: () => 'session-a' }
})

test('unreliable evidence is an explicit server error and does not manufacture questions', async () => {
  const workspace = createLearningExercises({ mediaId: 1, goal: 'goal', request: async () => ({ ok: false, text: async () => '没有可靠证据' }) })
  await workspace.generate()
  assert.equal(workspace.state.error, '没有可靠证据')
  assert.equal(workspace.state.exercises.length, 0)
})
