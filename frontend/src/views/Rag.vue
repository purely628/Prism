<template>
  <div class="rag-page">
    <el-card shadow="never" class="page-card">
      <div class="rag-toolbar">
        <div class="rag-toolbar-left">
          <span class="card-title">知识库：</span>
          <el-select
            v-model="currentKbId"
            placeholder="请选择知识库"
            style="width: 260px"
            @change="handleKbChange"
          >
            <el-option v-for="kb in kbList" :key="kb.id" :label="kb.kbName" :value="kb.id">
              <span>{{ kb.kbName }}</span>
              <span class="kb-option-meta">topK {{ kb.topK }}</span>
            </el-option>
          </el-select>
        </div>
        <el-button :icon="Delete" plain @click="clearChat">清空对话</el-button>
      </div>
    </el-card>

    <el-card shadow="never" class="chat-card">
      <div ref="msgListEl" class="msg-list">
        <div v-for="(m, idx) in messages" :key="idx" class="msg-row" :class="m.role">
          <div class="avatar" :class="m.role">
            <el-icon v-if="m.role === 'user'"><User /></el-icon>
            <el-icon v-else><ChatDotRound /></el-icon>
          </div>
          <div class="bubble-wrap">
            <div class="bubble" :class="{ streaming: m === streamingMsg && streaming }">
              <span class="bubble-text">{{ m.content }}</span>
              <span v-if="m.role === 'assistant' && !m.content && !m.error" class="waiting">思考中…</span>
            </div>

            <!-- 引用列表 -->
            <el-collapse v-if="m.citations && m.citations.length" class="cite-collapse">
              <el-collapse-item :title="`引用来源 [1]-[${m.citations.length}]`" name="cite">
                <div v-for="(c, i) in m.citations" :key="i" class="cite-item">
                  <div class="cite-head">
                    <el-tag size="small" effect="dark">[{{ i + 1 }}]</el-tag>
                    <span class="cite-doc">{{ c.docName }} · 第 {{ c.chunkIndex }} 块</span>
                    <el-tag size="small" type="success" effect="plain">score {{ Number(c.score).toFixed(4) }}</el-tag>
                  </div>
                  <div class="cite-snippet">{{ c.snippet }}</div>
                </div>
              </el-collapse-item>
            </el-collapse>

            <!-- token 统计 -->
            <div v-if="m.stats" class="msg-stats">
              <el-tag size="small" type="info" effect="plain">prompt {{ m.stats.promptTokens ?? '-' }} tokens</el-tag>
              <el-tag size="small" type="info" effect="plain">completion {{ m.stats.completionTokens ?? '-' }} tokens</el-tag>
            </div>

            <div v-if="m.error" class="msg-error">
              <el-icon><WarningFilled /></el-icon>{{ m.error }}
            </div>
          </div>
        </div>
        <el-empty v-if="!messages.length" description="选择知识库，输入问题开始 RAG 问答" />
      </div>

      <div class="chat-input">
        <el-input
          v-model="question"
          type="textarea"
          :rows="3"
          placeholder="输入问题，Ctrl + Enter 发送"
          :disabled="streaming"
          resize="none"
          @keydown.ctrl.enter="send"
        />
        <div class="send-area">
          <span class="send-tip">基于检索增强生成，回答附带引用溯源</span>
          <el-button type="primary" :loading="streaming" :disabled="!currentKbId" @click="send">发 送</el-button>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Delete, User, ChatDotRound, WarningFilled } from '@element-plus/icons-vue'
import { listKbs } from '../api/kb'
import { ssePost } from '../utils/sse'

const kbList = ref([])
const currentKbId = ref(null)

const messages = ref([])
const streamingMsg = ref(null)
const question = ref('')
const streaming = ref(false)
const msgListEl = ref()

function handleKbChange() {
  /* 切换知识库不强制清空对话，保留上下文记录 */
}

function clearChat() {
  if (streaming.value) {
    ElMessage.warning('回答生成中，请稍候')
    return
  }
  messages.value = []
}

async function scrollBottom() {
  await nextTick()
  const el = msgListEl.value
  if (el) el.scrollTop = el.scrollHeight
}

function send() {
  const q = question.value.trim()
  if (!q) return
  if (!currentKbId.value) {
    ElMessage.warning('请先选择知识库')
    return
  }
  if (streaming.value) return

  messages.value.push({ role: 'user', content: q })
  const msg = reactive({ role: 'assistant', content: '', citations: null, stats: null, error: null })
  messages.value.push(msg)
  streamingMsg.value = msg
  question.value = ''
  streaming.value = true
  scrollBottom()

  ssePost(`/api/kb/${currentKbId.value}/chat`, { question: q, stream: true }, {
    onEvent(name, data) {
      if (name === 'chunk') {
        msg.content += data?.delta ?? ''
      } else if (name === 'citations') {
        msg.citations = Array.isArray(data) ? data : []
      } else if (name === 'done') {
        msg.stats = data
      } else if (name === 'error') {
        msg.error = data?.message || '服务返回错误'
      }
      scrollBottom()
    },
    onDone() {
      streaming.value = false
      streamingMsg.value = null
      scrollBottom()
    },
    onError(e) {
      msg.error = e?.message || '连接失败'
      streaming.value = false
      streamingMsg.value = null
    }
  })
}

onMounted(async () => {
  const list = (await listKbs().catch(() => null)) || []
  kbList.value = list
  if (list.length) currentKbId.value = list[0].id
})
</script>

<style scoped>
.rag-page {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 60px - 32px);
}

.rag-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.rag-toolbar-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.kb-option-meta {
  float: right;
  color: #909399;
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
  background: linear-gradient(135deg, #9333ea, #c084fc);
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
  color: #303133;
  border: 1px solid #d9ecff;
}

.msg-row.assistant .bubble {
  background: #fff;
  border: 1px solid #e4e7ed;
}

.bubble.streaming::after {
  content: '▍';
  color: #9333ea;
  animation: blink 0.8s infinite;
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}

.waiting {
  color: #c0c4cc;
}

.cite-collapse {
  margin-top: 8px;
  border: none;
}

.cite-collapse :deep(.el-collapse-item__header) {
  font-size: 13px;
  color: #909399;
  height: 34px;
  line-height: 34px;
  background: transparent;
  border-bottom: none;
}

.cite-collapse :deep(.el-collapse-item__wrap) {
  background: transparent;
  border-bottom: none;
}

.cite-collapse :deep(.el-collapse-item__content) {
  padding-bottom: 4px;
}

.cite-item {
  border: 1px dashed #e4e7ed;
  border-radius: 6px;
  padding: 8px 10px;
  margin-bottom: 8px;
  background: #fafbfc;
}

.cite-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.cite-doc {
  flex: 1;
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cite-snippet {
  font-size: 12px;
  color: #909399;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 120px;
  overflow-y: auto;
  line-height: 1.6;
}

.msg-stats {
  margin-top: 8px;
  display: flex;
  gap: 8px;
}

.msg-error {
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
  justify-content: space-between;
  align-items: center;
  margin-top: 8px;
}

.send-tip {
  color: #c0c4cc;
  font-size: 12px;
}
</style>
