<template>
  <el-row :gutter="16">
    <!-- 左：知识库列表 -->
    <el-col :span="7">
      <el-card shadow="never" class="kb-left">
        <template #header>
          <div class="page-header">
            <span class="card-title">知识库</span>
            <el-button type="primary" size="small" :icon="Plus" @click="openCreate">新建</el-button>
          </div>
        </template>
        <div v-loading="kbLoading" class="kb-list">
          <div
            v-for="kb in kbList"
            :key="kb.id"
            class="kb-item"
            :class="{ active: kb.id === currentKbId }"
            @click="selectKb(kb)"
          >
            <div class="kb-item-name">
              <el-icon><FolderOpened v-if="kb.id === currentKbId" /><Folder v-else /></el-icon>
              {{ kb.kbName }}
            </div>
            <div class="kb-item-desc">{{ kb.description || '暂无描述' }}</div>
            <div class="kb-item-meta">chunk {{ kb.chunkSize }} / overlap {{ kb.chunkOverlap }} / topK {{ kb.topK }}</div>
          </div>
          <el-empty v-if="!kbLoading && !kbList.length" description="暂无知识库" :image-size="60" />
        </div>
      </el-card>
    </el-col>

    <!-- 右：文档 + 检索测试 -->
    <el-col :span="17">
      <el-card shadow="never" class="page-card">
        <template #header>
          <div class="page-header">
            <span class="card-title">
              文档列表
              <el-tag v-if="currentKb" size="small" effect="plain" style="margin-left: 8px">{{ currentKb.kbName }}</el-tag>
            </span>
            <div v-if="currentKb">
              <el-button size="small" :icon="Delete" type="danger" plain @click="handleDeleteKb">删除知识库</el-button>
              <el-button size="small" :icon="Refresh" type="primary" plain @click="loadDocs">刷新状态</el-button>
            </div>
          </div>
        </template>

        <template v-if="currentKb">
          <el-upload
            drag
            class="upload-box"
            :http-request="doUpload"
            :show-file-list="false"
            accept=".txt,.md,.pdf,.doc,.docx"
            multiple
          >
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">拖拽文件到此处，或 <em>点击上传</em></div>
            <template #tip>
              <div class="el-upload__tip">支持 txt / md / pdf / docx，上传后自动解析切片并入向量库</div>
            </template>
          </el-upload>

          <el-table v-loading="docLoading" :data="docList" stripe style="margin-top: 14px">
            <el-table-column prop="docName" label="文档名" min-width="200" show-overflow-tooltip />
            <el-table-column label="大小" width="90" align="right">
              <template #default="{ row }">{{ formatFileSize(row.fileSize) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="110" align="center">
              <template #default="{ row }">
                <el-tooltip v-if="row.status === 'FAILED' && row.error" :content="row.error" placement="top">
                  <el-tag type="danger" effect="dark" size="small">FAILED</el-tag>
                </el-tooltip>
                <el-tag v-else :type="statusType(row.status)" :effect="row.status === 'READY' ? 'light' : 'plain'" size="small">
                  {{ row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="切块数" width="90" align="center">
              <template #default="{ row }">{{ row.chunkCount ?? 0 }}</template>
            </el-table-column>
            <el-table-column label="上传时间" width="170">
              <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="90" fixed="right">
              <template #default="{ row }">
                <el-button link type="danger" @click="handleDeleteDoc(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </template>
        <el-empty v-else description="请先在左侧选择或新建知识库" />
      </el-card>

      <el-card shadow="never">
        <template #header><span class="card-title">检索测试</span></template>
        <div class="retrieve-form">
          <el-input
            v-model="retrieveForm.query"
            placeholder="输入测试问题，验证召回效果"
            clearable
            :disabled="!currentKbId"
            @keyup.enter="doRetrieve"
          />
          <span class="retrieve-topk">topK</span>
          <el-input-number v-model="retrieveForm.topK" :min="1" :max="10" size="default" controls-position="right" style="width: 100px" />
          <el-button type="primary" :loading="retrieving" :disabled="!currentKbId" @click="doRetrieve">检索</el-button>
        </div>
        <div v-if="retrieveResults" class="retrieve-results">
          <div v-for="(r, i) in retrieveResults" :key="i" class="result-card">
            <div class="result-head">
              <el-tag size="small" type="primary" effect="dark">#{{ i + 1 }}</el-tag>
              <span class="result-doc">{{ r.docName }} · 第 {{ r.chunkIndex }} 块</span>
              <el-tag size="small" :type="scoreType(r.score)" effect="light">score {{ Number(r.score).toFixed(4) }}</el-tag>
            </div>
            <div class="result-content">{{ r.content || r.snippet }}</div>
          </div>
          <el-empty
            v-if="!retrieveResults.length"
            description="没有超过相似度阈值（0.15）的召回"
            :image-size="60"
          >
            <div class="empty-hint">
              当前离线 embedding 对字面不重叠的查询分数较低——换用文档中实际出现的关键词
              （如「令牌桶」「限流」「语义缓存」）即可命中；接真实 embedding 模型后此限制自然消失。
            </div>
          </el-empty>
        </div>
      </el-card>
    </el-col>

    <!-- 新建知识库 -->
    <el-dialog v-model="createVisible" title="新建知识库" width="480px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="110px">
        <el-form-item label="知识库名称" prop="kbName">
          <el-input v-model.trim="form.kbName" maxlength="64" placeholder="例如：公司规章制度" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model.trim="form.description" type="textarea" :rows="2" maxlength="255" placeholder="可选" />
        </el-form-item>
        <el-form-item label="chunk 大小">
          <el-input-number v-model="form.chunkSize" :min="100" :max="4000" :step="100" controls-position="right" />
          <span class="form-tip-inline">每块字符数</span>
        </el-form-item>
        <el-form-item label="重叠字符数">
          <el-input-number v-model="form.chunkOverlap" :min="0" :max="500" :step="10" controls-position="right" />
        </el-form-item>
        <el-form-item label="默认 topK">
          <el-input-number v-model="form.topK" :min="1" :max="10" controls-position="right" />
          <span class="form-tip-inline">RAG 对话默认召回条数</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleCreate">创建</el-button>
      </template>
    </el-dialog>
  </el-row>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Delete, UploadFilled, Folder, FolderOpened } from '@element-plus/icons-vue'
import { listKbs, createKb, deleteKb, listDocs, uploadDoc, deleteDoc, retrieve } from '../api/kb'
import { formatDateTime } from '../utils/format'

const kbLoading = ref(false)
const docLoading = ref(false)
const submitting = ref(false)
const retrieving = ref(false)

const kbList = ref([])
const currentKbId = ref(null)
const currentKb = ref(null)
const docList = ref([])

const createVisible = ref(false)
const formRef = ref()
const form = reactive({ kbName: '', description: '', chunkSize: 500, chunkOverlap: 50, topK: 4 })
const formRules = {
  kbName: [{ required: true, message: '请输入知识库名称', trigger: 'blur' }]
}

const retrieveForm = reactive({ query: '', topK: 4 })
const retrieveResults = ref(null)

function statusType(s) {
  return { PENDING: 'info', PARSING: 'primary', READY: 'success', FAILED: 'danger' }[s] || 'info'
}

function scoreType(score) {
  const v = Number(score) || 0
  if (v >= 0.75) return 'success'
  if (v >= 0.5) return 'warning'
  return 'info'
}

function formatFileSize(size) {
  const v = Number(size) || 0
  if (v >= 1024 * 1024) return `${(v / 1024 / 1024).toFixed(2)} MB`
  if (v >= 1024) return `${(v / 1024).toFixed(1)} KB`
  return `${v} B`
}

async function loadKbs() {
  kbLoading.value = true
  try {
    kbList.value = (await listKbs()) || []
    if (currentKbId.value && !kbList.value.some((k) => k.id === currentKbId.value)) {
      currentKbId.value = null
      currentKb.value = null
      docList.value = []
    }
  } finally {
    kbLoading.value = false
  }
}

function selectKb(kb) {
  currentKbId.value = kb.id
  currentKb.value = kb
  retrieveForm.topK = kb.topK || 4
  retrieveResults.value = null
  loadDocs()
}

async function loadDocs() {
  if (!currentKbId.value) return
  docLoading.value = true
  try {
    docList.value = (await listDocs(currentKbId.value)) || []
  } finally {
    docLoading.value = false
  }
}

function openCreate() {
  Object.assign(form, { kbName: '', description: '', chunkSize: 500, chunkOverlap: 50, topK: 4 })
  createVisible.value = true
}

function handleCreate() {
  formRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      const kb = await createKb({ ...form })
      createVisible.value = false
      ElMessage.success('知识库已创建')
      await loadKbs()
      if (kb?.id) {
        selectKb(kb)
      }
    } catch (e) {
      /* request.js 已提示 */
    } finally {
      submitting.value = false
    }
  })
}

async function doUpload({ file, onSuccess, onError }) {
  if (!currentKbId.value) return
  try {
    await uploadDoc(currentKbId.value, file)
    ElMessage.success(`「${file.name}」上传成功，已进入解析队列`)
    onSuccess && onSuccess()
    await loadDocs()
  } catch (e) {
    onError && onError(e)
  }
}

async function handleDeleteDoc(row) {
  try {
    await ElMessageBox.confirm(`确定删除文档「${row.docName}」吗？将同时清除其向量数据。`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  await deleteDoc(currentKbId.value, row.id)
  ElMessage.success('已删除')
  await loadDocs()
}

async function handleDeleteKb() {
  if (!currentKb.value) return
  try {
    await ElMessageBox.confirm(`确定删除知识库「${currentKb.value.kbName}」及其全部文档与向量吗？不可恢复。`, '删除确认', { type: 'error', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch {
    return
  }
  await deleteKb(currentKbId.value)
  ElMessage.success('知识库已删除')
  currentKbId.value = null
  currentKb.value = null
  docList.value = []
  await loadKbs()
}

async function doRetrieve() {
  const q = retrieveForm.query.trim()
  if (!q || !currentKbId.value) return
  retrieving.value = true
  try {
    retrieveResults.value = (await retrieve(currentKbId.value, { query: q, topK: retrieveForm.topK })) || []
  } finally {
    retrieving.value = false
  }
}

onMounted(loadKbs)
</script>

<style scoped>
.kb-left {
  min-height: 560px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.kb-list {
  min-height: 420px;
}

.kb-item {
  padding: 10px 12px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  margin-bottom: 10px;
  cursor: pointer;
  transition: all 0.15s;
}

.kb-item:hover {
  border-color: #409eff;
}

.kb-item.active {
  border-color: #409eff;
  background: #ecf5ff;
}

.kb-item-name {
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 6px;
}

.kb-item-desc {
  color: #909399;
  font-size: 12px;
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kb-item-meta {
  color: #c0c4cc;
  font-size: 12px;
  margin-top: 4px;
}

.upload-box {
  width: 100%;
}

.upload-box :deep(.el-upload-dragger) {
  padding: 24px 0;
}

.retrieve-form {
  display: flex;
  gap: 10px;
  align-items: center;
}

.retrieve-topk {
  color: #909399;
  font-size: 13px;
  margin-left: 6px;
}

.retrieve-results {
  margin-top: 14px;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 12px;
}

.result-card {
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 10px 12px;
  background: #fafbfc;
}

.result-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.result-doc {
  flex: 1;
  font-weight: 600;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.result-content {
  font-size: 13px;
  color: #606266;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 180px;
  overflow-y: auto;
  line-height: 1.6;
}

.form-tip-inline {
  font-size: 12px;
  color: #909399;
  margin-left: 8px;
}

.empty-hint {
  font-size: 12px;
  color: #909399;
  line-height: 1.7;
  max-width: 420px;
  margin: 0 auto;
  text-align: left;
}
</style>
