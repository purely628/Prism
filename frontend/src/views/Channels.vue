<template>
  <el-card shadow="never">
    <template #header>
      <div class="page-header">
        <span class="card-title">模型渠道（管理员）</span>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建渠道</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="list" stripe>
      <el-table-column prop="channelName" label="渠道名" min-width="140" />
      <el-table-column label="Provider" width="150" align="center">
        <template #default="{ row }">
          <el-tag :type="providerTag(row.provider)" effect="light">{{ providerLabel(row.provider) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="baseUrl" label="上游地址" min-width="200" show-overflow-tooltip />
      <el-table-column prop="modelName" label="上游模型" min-width="120" />
      <el-table-column label="对外别名" width="140">
        <template #default="{ row }">
          <el-tag type="primary" effect="plain">{{ row.alias }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="weight" label="权重" width="80" align="center" />
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
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog.visible" :title="dialog.id ? '编辑渠道' : '新建渠道'" width="540px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="渠道名" prop="channelName">
          <el-input v-model.trim="form.channelName" maxlength="64" placeholder="例如：GLM-4-Flash" />
        </el-form-item>
        <el-form-item label="Provider" prop="provider">
          <el-select v-model="form.provider" style="width: 100%">
            <el-option v-for="p in providers" :key="p.value" :label="p.label" :value="p.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="上游地址" prop="baseUrl">
          <el-input v-model.trim="form.baseUrl" placeholder="https://... 或 mock://local" />
        </el-form-item>
        <el-form-item label="上游密钥" prop="apiKey">
          <el-input v-model.trim="form.apiKey" type="password" show-password placeholder="sk-...（mock 渠道可留空）" autocomplete="new-password" />
        </el-form-item>
        <el-form-item label="上游模型名" prop="modelName">
          <el-input v-model.trim="form.modelName" placeholder="例如 glm-4-flash" />
        </el-form-item>
        <el-form-item label="对外别名" prop="alias">
          <el-input v-model.trim="form.alias" placeholder="客户端请求中的 model 值，如 prism-demo" />
        </el-form-item>
        <el-form-item label="权重" prop="weight">
          <el-input-number v-model="form.weight" :min="1" :max="100" controls-position="right" />
          <span class="form-tip">同别名多渠道时按权重加权路由</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { listChannels, createChannel, updateChannel, setChannelStatus } from '../api/channels'

const providers = [
  { value: 'glm', label: '智谱 GLM' },
  { value: 'deepseek', label: 'DeepSeek' },
  { value: 'openai-compatible', label: 'OpenAI 兼容' },
  { value: 'mock', label: '内置 Mock' }
]

const providerTagMap = { glm: 'success', deepseek: 'primary', 'openai-compatible': 'warning', mock: 'info' }
const providerLabelMap = Object.fromEntries(providers.map((p) => [p.value, p.label]))

const loading = ref(false)
const submitting = ref(false)
const list = ref([])

const dialog = reactive({ visible: false, id: null })
const formRef = ref()
const form = reactive({
  channelName: '',
  provider: 'mock',
  baseUrl: '',
  apiKey: '',
  modelName: '',
  alias: '',
  weight: 1
})
const rules = {
  channelName: [{ required: true, message: '请输入渠道名', trigger: 'blur' }],
  provider: [{ required: true, message: '请选择 Provider', trigger: 'change' }],
  baseUrl: [{ required: true, message: '请输入上游地址', trigger: 'blur' }],
  modelName: [{ required: true, message: '请输入上游模型名', trigger: 'blur' }],
  alias: [{ required: true, message: '请输入对外别名', trigger: 'blur' }],
  weight: [{ required: true, message: '请输入权重', trigger: 'blur' }]
}

function providerTag(p) {
  return providerTagMap[p] || 'info'
}

function providerLabel(p) {
  return providerLabelMap[p] || p
}

async function load() {
  loading.value = true
  try {
    list.value = (await listChannels()) || []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(dialog, { visible: true, id: null })
  Object.assign(form, { channelName: '', provider: 'mock', baseUrl: 'mock://local', apiKey: '', modelName: '', alias: '', weight: 1 })
}

function openEdit(row) {
  Object.assign(dialog, { visible: true, id: row.id })
  Object.assign(form, {
    channelName: row.channelName,
    provider: row.provider,
    baseUrl: row.baseUrl,
    apiKey: row.apiKey ?? '',
    modelName: row.modelName,
    alias: row.alias,
    weight: row.weight ?? 1
  })
}

function handleSubmit() {
  formRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      if (dialog.id) {
        await updateChannel(dialog.id, { ...form })
        ElMessage.success('渠道已更新')
      } else {
        await createChannel({ ...form })
        ElMessage.success('渠道已创建')
      }
      dialog.visible = false
      await load()
    } catch (e) {
      /* request.js 已提示 */
    } finally {
      submitting.value = false
    }
  })
}

async function handleStatusChange(row, enabled) {
  try {
    await setChannelStatus(row.id, enabled ? 1 : 0)
    ElMessage.success(enabled ? '已启用' : '已禁用')
    await load()
  } catch (e) {
    await load()
  }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(`确定删除渠道「${row.channelName}」吗？`, '删除确认', { type: 'error', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch {
    return
  }
  // 契约未提供渠道 DELETE，使用 PUT 状态禁用兜底提示
  ElMessage.warning('当前契约未提供渠道删除接口，可先禁用该渠道')
}

onMounted(load)
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.form-tip {
  display: block;
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}
</style>
