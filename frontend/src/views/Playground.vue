<template>
  <div class="pg-page">
    <el-card shadow="never" class="page-card">
      <div class="settings">
        <div class="setting-item">
          <span class="setting-label">BaseURL</span>
          <el-input :model-value="settings.baseUrl" readonly class="baseurl-input">
            <template #suffix>
              <el-icon class="copy-btn" title="复制" @click="copyBase"><DocumentCopy /></el-icon>
            </template>
          </el-input>
        </div>
        <div class="setting-item">
          <span class="setting-label">API Key</span>
          <el-input v-model="settings.apiKey" show-password class="key-input" placeholder="sk-prism-..." />
        </div>
        <div class="setting-item">
          <span class="setting-label">模型</span>
          <el-select v-model="settings.model" style="width: 180px">
            <el-option v-for="m in models" :key="m" :label="m" :value="m" />
          </el-select>
        </div>
      </div>
      <div class="settings-tip">OpenAI 兼容：任何 OpenAI SDK 把 base_url 指向上述地址、API Key 换成应用 appKey 即可接入</div>
    </el-card>

    <el-card shadow="never" class="chat-card">
      <div ref="msgListEl" class="msg-list">
        <div v-for="(m, idx) in messages" :key="idx" class="msg-row" :class="m.role">
          <div class="avatar" :class="m.role">
            <el-icon v-if="m.role === 'user'"><User /></el-icon>
            <el-icon v-else><MagicStick /></el-icon>
          </div>
          <div class="bubble-wrap">
            <div class="bubble" :class="{ streaming: m === streamingMsg && streaming }">{{ m.content }}</div>
            <div v-if="m.role === 'assistant' && (m.usage || m.elapsedMs != null)" class="bubble-footer">
              <template v-if="m.usage">
                <span>prompt {{ m.usage.prompt_tokens ?? '-' }}</span>
                <span>completion {{ m.usage.completion_tokens ?? '-' }}</span>
                <span>total {{ m.usage.total_tokens ?? '-' }} tokens</span>
                <span class="divider">·</span>
              </template>
              <span v-if="m.elapsedMs != null">本段耗时 {{ (m.elapsedMs / 1000).toFixed(1) }}s</span>
            </div>
            <div v-if="m.error" class="bubble-error">
              <el-icon><WarningFilled /></el-icon>{{ m.error }}
            </div>
          </div>
        </div>
        <el-empty v-if="!messages.length" description="直接调用 /v1/chat/completions（stream）体验 OpenAI 兼容接口" />
      </div>

      <div class="chat-input">
        <el-input
          v-model="input"
          type="textarea"
          :rows="3"
          placeholder="输入消息，Ctrl + Enter 发送（携带完整对话上下文）"
          :disabled="streaming"
          resize="none"
          @keydown.ctrl.enter="send"
        />
        <div class="send-area">
          <el-button text :disabled="streaming || !messages.length" @click="clearChat">清空对话</el-button>
          <el-button type="primary" :loading="streaming" @click="send">发 送</el-button>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { nextTick, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { User, MagicStick, WarningFilled, DocumentCopy } from '@element-plus/icons-vue'
import { ssePost } from '../utils/sse'
import { copyText } from '../utils/format'

const DEFAULT_KEY = 'sk-prism-demo0000demo0000demo0000demo0000'

const settings = reactive({
  baseUrl: 'http://localhost:8080/v1',
  apiKey: DEFAULT_KEY,
  model: 'prism-demo'
})
const models = ['prism-demo', 'glm-4-flash']

const messages = ref([])
const streamingMsg = ref(null)
const streaming = ref(false)
const input = ref('')
const msgListEl = ref()

async function scrollBottom() {
  await nextTick()
  const el = msgListEl.value
  if (el) el.scrollTop = el.scrollHeight
}

async function copyBase() {
  const ok = await copyText(settings.baseUrl)
  ok ? ElMessage.success('已复制') : ElMessage.error('复制失败')
}

function clearChat() {
  if (streaming.value) return
  messages.value = []
}

function send() {
  const text = input.value.trim()
  if (!text || streaming.value) return
  if (!settings.apiKey) {
    ElMessage.warning('请填写 API Key（应用 appKey）')
    return
  }

  messages.value.push({ role: 'user', content: text })
  const assistant = reactive({ role: 'assistant', content: '', usage: null, elapsedMs: null, error: null })
  messages.value.push(assistant)
  streamingMsg.value = assistant
  input.value = ''
  streaming.value = true
  scrollBottom()

  // 携带完整上下文（剔除出错/为空的气泡）
  const history = messages.value
    .filter((m) => m.content && !m.error)
    .map((m) => ({ role: m.role, content: m.content }))

  const t0 = Date.now()
  ssePost(
    '/v1/chat/completions',
    { model: settings.model, messages: history, stream: true },
    {
      headers: { Authorization: `Bearer ${settings.apiKey}` },
      onEvent(name, data) {
        if (name !== 'message' || !data) return
        // OpenAI 流式：choices[0].delta.content 增量；部分实现最后一块带 usage
        const delta = data.choices?.[0]?.delta?.content
        if (delta) assistant.content += delta
        if (data.usage) assistant.usage = data.usage
        scrollBottom()
      },
      onDone() {
        assistant.elapsedMs = Date.now() - t0
        streaming.value = false
        streamingMsg.value = null
        scrollBottom()
      },
      onError(e) {
        assistant.error = e?.message || '请求失败'
        streaming.value = false
        streamingMsg.value = null
      }
    }
  )
}
</script>

<style scoped>
.pg-page {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 60px - 32px);
}

.settings {
  display: flex;
  gap: 20px;
  align-items: center;
  flex-wrap: wrap;
}

.setting-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.setting-label {
  color: #909399;
  font-size: 13px;
  white-space: nowrap;
}

.baseurl-input {
  width: 280px;
}

.copy-btn {
  cursor: pointer;
  color: #409eff;
}

.key-input {
  width: 360px;
}

.settings-tip {
  margin-top: 10px;
  color: #c0c4cc;
  font-size: 12px;
}

.chat-card {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.chat-card :deep(.el-card__body) {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.msg-list {
  flex: 1;
  overflow-y: auto;
  padding: 6px 4px;
}

.msg-row {
  display: flex;
  gap: 10px;
  margin-bottom: 18px;
}

.msg-row.user {
  flex-direction: row-reverse;
}

.avatar {
  width: 34px;
  height: 34px;
  border-radius: 8px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 17px;
}

.avatar.user {
  background: linear-gradient(135deg, #409eff, #79bbff);
}

.avatar.assistant {
  background: linear-gradient(135deg, #13c2c2, #409eff);
}

.bubble-wrap {
  max-width: 78%;
  min-width: 0;
}

.msg-row.user .bubble-wrap {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.bubble {
  padding: 10px 14px;
  border-radius: 10px;
  line-height: 1.7;
  font-size: 14px;
  word-break: break-word;
  white-space: pre-wrap;
}

.msg-row.user .bubble {
  background: #ecf5ff;
  border: 1px solid #d9ecff;
}

.msg-row.assistant .bubble {
  background: #fff;
  border: 1px solid #e4e7ed;
}

.bubble.streaming::after {
  content: '▍';
  color: #13c2c2;
  animation: blink 0.8s infinite;
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}

.bubble-footer {
  margin-top: 6px;
  display: flex;
  gap: 10px;
  color: #909399;
  font-size: 12px;
  flex-wrap: wrap;
}

.bubble-footer .divider {
  color: #dcdfe6;
}

.bubble-error {
  margin-top: 6px;
  color: #f56c6c;
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 4px;
}

.chat-input {
  border-top: 1px solid #ebeef5;
  padding-top: 12px;
  margin-top: 8px;
}

.send-area {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 8px;
}
</style>
