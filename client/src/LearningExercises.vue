<template>
  <section class="learning-exercises" aria-label="视频练习与错题">
    <h3>原文练习</h3>
    <p class="exercise-caption">填写视频中的原词，提交后查看知识点。回看定位到证据片段起点（分钟级）。</p>
    <div class="learning-actions">
      <button class="generate-button" type="button" :disabled="state.busy" @click="workspace.generate">生成 / 载入练习</button>
      <button type="button" :disabled="state.busy" @click="workspace.load">刷新记录</button>
    </div>
    <p v-if="state.error" role="alert">{{ state.error }}</p>
    <p v-if="state.busy" role="status">正在处理学习记录…</p>
    <p v-if="!state.exercises.length && !state.busy" class="empty-state">暂无练习，点击上方按钮开始。</p>
    <form v-for="(exercise, index) in state.exercises" :key="exercise.id" @submit.prevent="workspace.submit(exercise)">
      <span class="question-index">练习 {{ String(index + 1).padStart(2, '0') }}</span>
      <label :for="`answer-${exercise.id}`">{{ exercise.prompt }}</label>
      <input :id="`answer-${exercise.id}`" v-model="state.answers[exercise.id]" maxlength="128"
             autocomplete="off" required :disabled="state.busy" placeholder="填写原词" />
      <button :disabled="state.busy" type="submit">提交答案</button>
    </form>
    <div class="history-heading"><h4>答题记录</h4>
      <label class="wrong-filter"><input v-model="state.wrongOnly" type="checkbox" :disabled="state.busy" @change="workspace.history()" />只看错题</label>
    </div>
    <p v-if="!state.attempts.length && !state.busy" class="empty-state">{{ state.wrongOnly ? '当前没有错题记录。' : '完成一道练习，记录会保存在这里。' }}</p>
    <article v-for="attempt in state.attempts" :key="attempt.id">
      <strong class="answer-badge" :class="attempt.correct ? 'is-correct' : 'is-wrong'">{{ attempt.correct ? '✓ 回答正确' : '需复习' }}</strong>
      <p>{{ attempt.prompt }}</p>
      <p>你的答案：{{ attempt.submittedAnswer }}；参考答案：{{ attempt.expectedAnswer }}</p>
      <p class="knowledge-point">知识点：{{ attempt.knowledgePoint }}</p>
      <blockquote>{{ attempt.evidenceText }}</blockquote>
      <button class="review-button" type="button" :disabled="!playbackReady" @click="$emit('seek', attempt.timestampMs)">
        ▶ 回看原视频 {{ formatTime(attempt.timestampMs) }}
      </button>
      <small v-if="!playbackReady">原视频尚未载入，请先重试视频播放</small>
    </article>
    <button v-if="state.more" type="button" :disabled="state.busy" @click="workspace.history(true)">载入更早记录</button>
  </section>
</template>

<script setup>
import { onBeforeUnmount, onMounted } from 'vue'
import { createLearningExercises } from './learningExercises.js'

const props = defineProps({ mediaId: { type: Number, required: true }, goal: { type: String, required: true }, playbackReady: Boolean })
defineEmits(['seek'])
// App keys this component by user/media/goal: closing, switching or logging out disposes pending responses.
const workspace = createLearningExercises({ mediaId: props.mediaId, goal: props.goal })
const state = workspace.state
const formatTime = ms => `${Math.floor(ms / 60000)}:${String(Math.floor(ms / 1000) % 60).padStart(2, '0')}`
onMounted(workspace.load)
onBeforeUnmount(workspace.stop)
</script>

<style scoped>
.learning-exercises { padding: 19px 0 14px; }
h3, h4 { font-size: 13px; font-weight: 600; }
p, label, blockquote { white-space: pre-wrap; overflow-wrap: anywhere; }
.exercise-caption { margin-top: 7px; font-size: 10px; line-height: 1.9; color: #8b98ad; }
.learning-actions { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 6px; margin: 14px 0 17px; }
button { font-size: 10px; padding: 7px 8px; }
.generate-button { color: #fff; background: #3669e8; border-color: #3669e8; }
.generate-button:hover:not(:disabled) { color: #fff; background: #2a59cf; border-color: #2a59cf; }
.empty-state { padding: 19px 12px; margin: 10px 0; border: 1px dashed #e1e8f3; background: #fafcfe; border-radius: 8px; text-align: center; font-size: 11px; color: #8c99ad; }
form { margin: 12px 0; padding: 14px; border: 1px solid #e3eaf5; background: #fafcfe; border-radius: 9px; }
.question-index { color: #7892ba; display: block; font-size: 10px; margin-bottom: 7px; }
form label { display: block; margin-bottom: 12px; font-size: 12px; line-height: 1.9; }
input:not([type=checkbox]) { width: 100%; min-width: 0; margin-bottom: 8px; padding: 8px 10px; font-size: 11px; background: #fff; color: #24324b; border: 1px solid #dce4f2; border-radius: 6px; }
form > button { width: 100%; color: #4c76c7; background: #edf3ff; border-color: #dfe8fa; }
.history-heading { display: flex; align-items: center; justify-content: space-between; padding: 21px 0 12px; margin-top: 7px; border-top: 1px solid #e9edf5; gap: 8px; }
.wrong-filter { display: flex; align-items: center; gap: 5px; font-size: 10px; color: #8391a6; cursor: pointer; }
.wrong-filter input { margin: 0; }
article { margin: 12px 0; padding: 13px; border: 1px solid #e7ebf3; border-radius: 9px; }
.answer-badge { display: inline-block; font-size: 10px; padding: 2px 7px; border-radius: 5px; margin-bottom: 4px; }
.is-correct { color: #328364; background: #ecf7ef; }
.is-wrong { color: #ae7a32; background: #fcf3e5; }
article p { font-size: 11px; line-height: 1.85; margin: 8px 0; color: #657692; }
article .knowledge-point { color: #3c567f; font-weight: 600; }
blockquote { margin: 10px 0; padding: 8px 10px; border-left: 2px solid #afc6ed; background: #f4f7fd; color: #7588a6; font-size: 10px; line-height: 1.85; }
.review-button { color: #4c78ce; background: #eff4ff; border-color: #e3ebfa; font-variant-numeric: tabular-nums; }
small { display: block; font-size: 10px; margin-top: 6px; color: #97a3b4; }
[role=alert] { padding: 10px; border: 1px solid #f0d9d3; border-radius: 7px; background: #fff5f2; color: #ad5145; font-size: 11px; margin: 10px 0; }
[role=status] { font-size: 11px; color: #7c90b2; }
</style>
