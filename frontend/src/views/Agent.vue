<template>
  <div class="agent-page">
    <el-card shadow="never" class="page-card">
      <div class="run-bar">
        <el-input
          v-model="goal"
          type="textarea"
          :rows="2"
          placeholder="输入任务目标，例如：查一下公司年假制度并总结成 3 条要点"
          resize="none"
          :disabled="running"
          @keydown.ctrl.enter="run"
        />
        <div class="run-actions">
          <span class="run-tip">ReAct 模式：思考 → 工具调用 → 观察 → 作答，全程流式展示</span>
          <el-button type="primary" size="large" :loading="running" :icon="VideoPlay" @click="run">运 行</el-button>
        </div>
      </div>
    </el-card>

    <el-card shadow="never" class="timeline-card">
      <template #header><span class="card-title">执行时间线</span></template>
      <el-empty v-if="!steps.length" description="输入目标并点击运行，查看 Agent 思考过程" />
      <el-timeline v-else>
        <el-timeline-item
          v-for="(s, i) in steps"
          :key="i"
          :timestamp="s.time"
          placement="top"
          :color="stepColor(s.type)"
          :hollow="s.type === 'observation'"
        >
          <div class="step-card" :class="s.type">
            <!-- 思考 -->
            <template v-if="s.type === 'thought'">
              <div class="step-label thought">
                <el-icon><MagicStick /></el-icon>思考
              </div>
              <div class="thought-text">{{ s.text }}</div>
            </template>

            <!-- 动作 -->
            <template v-else-if="s.type === 'action'">
              <div class="step-label action">
                <el-icon><Lightning /></el-icon>动作
              </div>
              <div class="action-body">
                <el-tag type="warning" effect="dark" size="small" class="tool-tag">{{ s.tool || 'tool' }}</el-tag>
                <span class="action-input">{{ s.input }}</span>
              </div>
            </template>

            <!-- 观察（折叠） -->
            <template v-else-if="s.type === 'observation'">
              <el-collapse class="obs-collapse">
                <el-collapse-item name="obs">
                  <template #title>
                    <div class="step-label observation">
                      <el-icon><View /></el-icon>观察结果
                    </div>
                  </template>
                  <div class="obs-text">{{ s.output }}</div>
                </el-collapse-item>
              </el-collapse>
            </template>

            <!-- 最终回答（流式） -->
            <template v-else-if="s.type === 'answer'">
              <div class="step-label answer">
                <el-icon><EditPen /></el-icon>最终回答
                <span v-if="running && s === answerStep" class="answering">生成中…</span>
              </div>
              <div class="answer-text" :class="{ streaming: running && s === answerStep }">{{ s.text }}</div>
            </template>

            <!-- 完成 -->
            <template v-else-if="s.type === 'done'">
              <div class="step-label done">
                <el-icon><CircleCheckFilled /></el-icon>执行完成
              </div>
              <div class="done-stats">
                <el-tag type="success" effect="plain">总耗时 {{ s.elapsed }}</el-tag>
                <el-tag type="info" effect="plain">事件数 {{ steps.length - 1 }}</el-tag>
                <el-tag v-if="s.stats && s.stats.totalTokens != null" type="warning" effect="plain">
                  tokens {{ s.stats.totalTokens }}
                </el-tag>
              </div>
            </template>

            <!-- 错误 -->
            <template v-else-if="s.type === 'error'">
              <div class="step-label error">
                <el-icon><WarningFilled /></el-icon>执行出错
              </div>
              <div class="error-text">{{ s.text }}</div>
            </template>
          </div>
        </el-timeline-item>
      </el-timeline>
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { VideoPlay, MagicStick, Lightning, View, EditPen, CircleCheckFilled, WarningFilled } from '@element-plus/icons-vue'
import { ssePost } from '../utils/sse'
import { nowTime } from '../utils/format'

const goal = ref('')
const running = ref(false)
const steps = ref([])
const answerStep = ref(null)
const startTime = ref(0)

const colorMap = {
  thought: '#9333ea',
  action: '#e6a23c',
  observation: '#909399',
  answer: '#409eff',
  done: '#67c23a',
  error: '#f56c6c'
}

function stepColor(type) {
  return colorMap[type] || '#909399'
}

/** 契约只约定了事件名，data 内字段做兼容提取 */
function pickText(data, keys) {
  if (data == null) return ''
  if (typeof data === 'string') return data
  for (const k of keys) {
    if (data[k] != null) return String(data[k])
  }
  return ''
}

function run() {
  const g = goal.value.trim()
  if (!g) {
    ElMessage.warning('请输入任务目标')
    return
  }
  if (running.value) return

  steps.value = []
  answerStep.value = null
  running.value = true
  startTime.value = Date.now()

  ssePost('/api/agent/run', { goal: g, stream: true }, {
    onEvent(name, data) {
      if (name === 'thought') {
        steps.value.push({ type: 'thought', time: nowTime(), text: pickText(data, ['thought', 'text', 'content']) || '(空)' })
      } else if (name === 'action') {
        steps.value.push({
          type: 'action',
          time: nowTime(),
          tool: data?.tool || '',
          input: data?.input != null ? String(data.input) : ''
        })
      } else if (name === 'observation') {
        steps.value.push({ type: 'observation', time: nowTime(), output: pickText(data, ['output', 'observation', 'text', 'content']) || '(空)' })
      } else if (name === 'answer') {
        if (!answerStep.value) {
          answerStep.value = { type: 'answer', time: nowTime(), text: '' }
          steps.value.push(answerStep.value)
        }
        answerStep.value.text += data?.delta ?? ''
      } else if (name === 'done') {
        steps.value.push({
          type: 'done',
          time: nowTime(),
          stats: data && typeof data === 'object' ? data : null,
          elapsed: `${((Date.now() - startTime.value) / 1000).toFixed(1)}s`
        })
      } else if (name === 'error') {
        steps.value.push({ type: 'error', time: nowTime(), text: data?.message || '服务返回错误' })
      }
    },
    onDone() {
      running.value = false
    },
    onError(e) {
      steps.value.push({ type: 'error', time: nowTime(), text: e?.message || '连接失败' })
      running.value = false
    }
  })
}
</script>

<style scoped>
.run-bar {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.run-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.run-tip {
  color: #c0c4cc;
  font-size: 12px;
}

.timeline-card :deep(.el-card__body) {
  max-height: calc(100vh - 300px);
  overflow-y: auto;
}

.step-card {
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 10px 14px;
  background: #fafbfc;
}

.step-card.answer {
  border-color: #d9ecff;
  background: #fff;
}

.step-label {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  font-weight: 700;
  margin-bottom: 6px;
}

.step-label.thought {
  color: #9333ea;
}

.step-label.action {
  color: #e6a23c;
}

.step-label.observation {
  color: #909399;
  margin-bottom: 0;
}

.step-label.answer {
  color: #409eff;
}

.step-label.done {
  color: #67c23a;
}

.step-label.error {
  color: #f56c6c;
}

.thought-text {
  color: #7c3aed;
  line-height: 1.7;
  white-space: pre-wrap;
}

.action-body {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
}

.tool-tag {
  font-family: Consolas, Monaco, monospace;
}

.action-input {
  font-family: Consolas, Monaco, monospace;
  color: #b45309;
  word-break: break-all;
  line-height: 1.7;
}

.obs-collapse {
  border: none;
}

.obs-collapse :deep(.el-collapse-item__header) {
  background: transparent;
  border: none;
  height: 24px;
  line-height: 24px;
}

.obs-collapse :deep(.el-collapse-item__wrap) {
  background: transparent;
  border: none;
}

.obs-text {
  color: #909399;
  font-size: 13px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 220px;
  overflow-y: auto;
  line-height: 1.7;
}

.answering {
  color: #c0c4cc;
  font-weight: 400;
  margin-left: 8px;
}

.answer-text {
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
}

.answer-text.streaming::after {
  content: '▍';
  color: #409eff;
  animation: blink 0.8s infinite;
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}

.done-stats {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.error-text {
  color: #f56c6c;
  white-space: pre-wrap;
}
</style>
