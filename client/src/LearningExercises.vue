<template>
  <section class="learning-exercises" aria-label="视频练习与错题">
    <h3>视频练习与错题</h3>
    <p>依据已核验的视频原文填空；提交后查看知识点。跳转定位到证据片段起点（分钟级），并非逐字对齐。</p>
    <div class="learning-actions">
      <button type="button" :disabled="state.busy" @click="workspace.generate">生成 / 载入练习</button>
      <button type="button" :disabled="state.busy" @click="workspace.load">刷新学习记录</button>
    </div>
    <p v-if="state.error" role="alert">{{ state.error }}</p>
    <p v-if="state.busy" role="status">正在处理学习记录…</p>
    <p v-if="!state.exercises.length && !state.busy">暂无练习。先完成学习模式分析，再生成练习。</p>
    <form v-for="exercise in state.exercises" :key="exercise.id" @submit.prevent="workspace.submit(exercise)">
      <label :for="`answer-${exercise.id}`">{{ exercise.prompt }}</label>
      <input :id="`answer-${exercise.id}`" v-model="state.answers[exercise.id]" maxlength="128"
             autocomplete="off" required :disabled="state.busy" placeholder="填写原词" />
      <button :disabled="state.busy" type="submit">提交答案</button>
    </form>
    <h4>答题记录</h4>
    <label><input v-model="state.wrongOnly" type="checkbox" :disabled="state.busy" @change="workspace.history()" />只看错题</label>
    <p v-if="!state.attempts.length && !state.busy">暂无符合条件的答题记录。</p>
    <article v-for="attempt in state.attempts" :key="attempt.id">
      <strong>{{ attempt.correct ? '回答正确' : '回答错误' }}</strong>
      <p>{{ attempt.prompt }}</p>
      <p>你的答案：{{ attempt.submittedAnswer }}；参考答案：{{ attempt.expectedAnswer }}</p>
      <p>知识点：{{ attempt.knowledgePoint }}</p>
      <blockquote>{{ attempt.evidenceText }}</blockquote>
      <button type="button" :disabled="!playbackReady" @click="$emit('seek', attempt.timestampMs)">
        回看原视频 {{ formatTime(attempt.timestampMs) }}
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
.learning-exercises { margin: 20px 0; padding: 16px; border: 1px solid currentColor; border-radius: 10px; }
p, label, blockquote { white-space: pre-wrap; overflow-wrap: anywhere; }
form, article { border-top: 1px solid #7775; padding: 14px 0; }
form label { display: block; margin-bottom: 8px; }
input:not([type=checkbox]) { max-width: 100%; padding: 8px; }
button { cursor: pointer; padding: 7px 10px; margin: 4px; }
button:disabled { cursor: default; opacity: .6; }
small { display: block; }
</style>
