import { reactive } from 'vue'
import { apiRequest, captureAuthSession } from './api.js'

export function createLearningExercises({ mediaId, goal, request = apiRequest, uuid = () => crypto.randomUUID() }) {
  const state = reactive({ exercises: [], attempts: [], answers: {}, error: '', busy: false, more: false, wrongOnly: false })
  const scope = new URLSearchParams({ mediaId: String(mediaId), goal })
  const currentSession = captureAuthSession()
  const receipts = new Map()
  let stopped = false
  const current = () => !stopped && currentSession()
  const json = async (path, options) => {
    const response = await request(`/analysis/learning${path}`, options)
    if (!response.ok) throw new Error(await response.text() || '学习请求失败，请重试')
    return response.json()
  }
  const run = async action => {
    if (!current() || state.busy) return
    state.busy = true
    state.error = ''
    try { await action() } catch (error) {
      if (current()) state.error = error.message || '学习请求失败，请重试'
    } finally { if (current()) state.busy = false }
  }
  const historyPath = beforeId => `/attempts?${scope}&wrongOnly=${state.wrongOnly}${beforeId ? `&beforeId=${beforeId}` : ''}`
  return {
    state,
    stop() { stopped = true },
    load: () => run(async () => {
      const [exercises, attempts] = await Promise.all([json(`/exercises?${scope}`), json(historyPath())])
      if (!current()) return
      state.exercises = exercises
      state.attempts = attempts
      state.more = attempts.length === 50
    }),
    generate: () => run(async () => {
      const exercises = await json('/exercises', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ mediaId, goal })
      })
      if (current()) state.exercises = exercises
    }),
    submit: exercise => run(async () => {
      const answer = (state.answers[exercise.id] || '').trim()
      if (!answer) throw new Error('请先填写答案')
      const previous = receipts.get(exercise.id)
      const receipt = previous?.answer === answer ? previous : { answer, requestId: uuid() }
      receipts.set(exercise.id, receipt) // retain on lost response so retry cannot create a duplicate attempt
      const result = await json(`/exercises/${encodeURIComponent(exercise.id)}/answers`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(receipt)
      })
      if (!current()) return
      if (!state.wrongOnly || !result.correct) {
        state.attempts = [result, ...state.attempts.filter(a => a.id !== result.id)]
      }
    }),
    history: (append = false) => run(async () => {
      const last = append ? state.attempts.at(-1)?.id : null
      const attempts = await json(historyPath(last))
      if (!current()) return
      state.attempts = append ? [...state.attempts, ...attempts] : attempts
      state.more = attempts.length === 50
    })
  }
}
