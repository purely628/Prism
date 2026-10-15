<template>
  <el-card shadow="never">
    <template #header>
      <div class="page-header">
        <span class="card-title">应用管理</span>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建应用</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="list" stripe>
      <el-table-column prop="appName" label="应用名" min-width="140">
        <template #default="{ row }">
          <span class="app-name">{{ row.appName }}</span>
          <el-tag size="small" type="info" effect="plain" style="margin-left: 8px">#{{ row.id }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="AppKey" min-width="240">
        <template #default="{ row }">
          <span class="key-text">{{ maskKey(row.appKey) }}</span>
          <el-button link type="primary" :icon="DocumentCopy" @click="handleCopy(row.appKey)" title="复制完整 Key" />
        </template>
      </el-table-column>
      <el-table-column label="月配额 (tokens)" width="130" align="right">
        <template #default="{ row }">{{ formatTokens(row.monthlyQuota) }}</template>
      </el-table-column>
      <el-table-column label="速率限制" width="100" align="center">
        <template #default="{ row }">{{ row.rateLimit }} 次/秒</template>
      </el-table-column>
      <el-table-column label="本月已用" min-width="200">
        <template #default="{ row }">
          <el-progress
            :percentage="usedPercent(row)"
            :color="usedColor(row)"
            :stroke-width="10"
            :format="() => formatTokens(usedOf(row))"
          />
          <div class="used-tip">占比 {{ usedPercent(row) }}%</div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-switch
            :model-value="row.status === 1"
            @change="(v) => handleStatusChange(row, v)"
            inline-prompt
            active-text="启"
            inactive-text="停"
          />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="handleResetKey(row)">重置 Key</el-button>
          <el-button link type="warning" @click="openQuota(row)">调整配额</el-button>
          <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新建应用 -->
    <el-dialog v-model="createVisible" title="新建应用" width="460px" destroy-on-close>
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="90px">
        <el-form-item label="应用名" prop="appName">
          <el-input v-model.trim="createForm.appName" maxlength="64" placeholder="例如：客服机器人" />
        </el-form-item>
        <el-form-item label="月配额" prop="monthlyQuota">
          <el-input-number v-model="createForm.monthlyQuota" :min="1000" :step="100000" controls-position="right" style="width: 100%" />
          <span class="form-tip">每月可用 tokens 总量</span>
        </el-form-item>
        <el-form-item label="速率限制" prop="rateLimit">
          <el-input-number v-model="createForm.rateLimit" :min="1" :max="1000" controls-position="right" style="width: 100%" />
          <span class="form-tip">每秒允许请求数（网关令牌桶速率）</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- Key 展示（新建/重置后一次性展示） -->
    <el-dialog v-model="keyDialog.visible" :title="keyDialog.title" width="520px" :close-on-click-modal="false">
      <el-alert type="warning" :closable="false" show-icon title="请立即保存，完整 Key 仅此一次展示" style="margin-bottom: 14px" />
      <div class="key-show">
        <span class="key-full">{{ keyDialog.key }}</span>
        <el-button type="primary" :icon="DocumentCopy" @click="handleCopy(keyDialog.key)">复制</el-button>
      </div>
    </el-dialog>

    <!-- 调整配额 -->
    <el-dialog v-model="quotaDialog.visible" title="调整月配额" width="440px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="应用">
          <el-input :model-value="quotaDialog.appName" disabled />
        </el-form-item>
        <el-form-item label="新月配额">
          <el-input-number v-model="quotaDialog.monthlyQuota" :min="1000" :step="100000" controls-position="right" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="quotaDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleQuotaSubmit">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, DocumentCopy } from '@element-plus/icons-vue'
import { listApps, createApp, resetAppKey, setAppStatus, setAppQuota, deleteApp } from '../api/apps'
import { formatTokens, maskKey, copyText } from '../utils/format'

const loading = ref(false)
const submitting = ref(false)
const list = ref([])

const createVisible = ref(false)
const createFormRef = ref()
const createForm = reactive({ appName: '', monthlyQuota: 1000000, rateLimit: 10 })
const createRules = {
  appName: [{ required: true, message: '请输入应用名', trigger: 'blur' }],
  monthlyQuota: [{ required: true, message: '请输入月配额', trigger: 'blur' }],
  rateLimit: [{ required: true, message: '请输入速率限制', trigger: 'blur' }]
}

const keyDialog = reactive({ visible: false, key: '', title: '应用 Key' })
const quotaDialog = reactive({ visible: false, id: null, appName: '', monthlyQuota: 0 })

function usedOf(row) {
  return Number(row.usedTokens ?? row.used ?? 0)
}

function usedPercent(row) {
  if (!row.monthlyQuota) return 0
  return Math.min(100, Math.round((usedOf(row) / row.monthlyQuota) * 100))
}

function usedColor(row) {
  const p = usedPercent(row)
  if (p >= 90) return '#f56c6c'
  if (p >= 70) return '#e6a23c'
  return '#67c23a'
}

async function load() {
  loading.value = true
  try {
    list.value = (await listApps()) || []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(createForm, { appName: '', monthlyQuota: 1000000, rateLimit: 10 })
  createVisible.value = true
}

function handleCreate() {
  createFormRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      const res = await createApp({ ...createForm })
      createVisible.value = false
      keyDialog.key = typeof res === 'string' ? res : res?.appKey || ''
      keyDialog.title = '创建成功，请保存 AppKey'
      keyDialog.visible = true
      await load()
    } catch (e) {
      /* request.js 已提示 */
    } finally {
      submitting.value = false
    }
  })
}

async function handleResetKey(row) {
  try {
    await ElMessageBox.confirm(`确定重置「${row.appName}」的 AppKey 吗？旧 Key 将立即失效。`, '重置 Key', { type: 'warning' })
  } catch {
    return
  }
  const res = await resetAppKey(row.id)
  keyDialog.key = typeof res === 'string' ? res : res?.appKey || ''
  keyDialog.title = '重置成功，请保存新 AppKey'
  keyDialog.visible = true
  await load()
}

async function handleStatusChange(row, enabled) {
  try {
    await setAppStatus(row.id, enabled ? 1 : 0)
    ElMessage.success(enabled ? '已启用' : '已禁用')
    await load()
  } catch (e) {
    await load()
  }
}

function openQuota(row) {
  Object.assign(quotaDialog, { visible: true, id: row.id, appName: row.appName, monthlyQuota: row.monthlyQuota })
}

async function handleQuotaSubmit() {
  submitting.value = true
  try {
    await setAppQuota(quotaDialog.id, quotaDialog.monthlyQuota)
    ElMessage.success('配额已更新')
    quotaDialog.visible = false
    await load()
  } catch (e) {
    /* request.js 已提示 */
  } finally {
    submitting.value = false
  }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(`确定删除应用「${row.appName}」吗？该操作不可恢复。`, '删除确认', { type: 'error', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch {
    return
  }
  await deleteApp(row.id)
  ElMessage.success('已删除')
  await load()
}

async function handleCopy(text) {
  const ok = await copyText(text)
  ok ? ElMessage.success('已复制到剪贴板') : ElMessage.error('复制失败，请手动复制')
}

onMounted(load)
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.app-name {
  font-weight: 600;
}

.key-text {
  font-family: Consolas, Monaco, monospace;
  color: #606266;
}

.used-tip {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.form-tip {
  display: block;
  font-size: 12px;
  color: #909399;
  line-height: 1.4;
  margin-top: 4px;
}

.key-show {
  display: flex;
  gap: 10px;
  align-items: center;
}

.key-full {
  flex: 1;
  word-break: break-all;
  font-family: Consolas, Monaco, monospace;
  background: #f5f7fa;
  border: 1px dashed #dcdfe6;
  border-radius: 6px;
  padding: 10px 12px;
}
</style>
