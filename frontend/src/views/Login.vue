<template>
  <div class="login-page">
    <el-card class="login-card">
      <div class="login-head">
        <div class="logo">Prism</div>
        <div class="sub">LLM 能力开放平台 · 控制台</div>
      </div>
      <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="handleLogin">
        <el-form-item prop="username">
          <el-input v-model.trim="form.username" placeholder="用户名" :prefix-icon="User" clearable />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model.trim="form.password" type="password" placeholder="密码" :prefix-icon="Lock" show-password />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" class="login-btn" :loading="loading" @click="handleLogin">登 录</el-button>
        </el-form-item>
      </el-form>
      <div class="login-tip">演示账号：admin / admin123</div>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { login } from '../api/auth'
import { useUserStore } from '../stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const formRef = ref()
const loading = ref(false)
const form = reactive({ username: 'admin', password: '' })
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

function handleLogin() {
  formRef.value.validate(async (valid) => {
    if (!valid) return
    loading.value = true
    try {
      const data = await login({ username: form.username, password: form.password })
      userStore.setAuth({
        token: data.token,
        nickname: data.nickname,
        username: form.username
      })
      ElMessage.success('登录成功')
      router.push(route.query.redirect || '/')
    } catch (e) {
      /* request.js 已统一提示 */
    } finally {
      loading.value = false
    }
  })
}
</script>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #0f2027 0%, #203a43 50%, #2c5364 100%);
}

.login-card {
  width: 400px;
  padding: 12px 8px;
  border-radius: 12px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.35);
}

.login-head {
  text-align: center;
  margin-bottom: 24px;
}

.logo {
  font-size: 34px;
  font-weight: 800;
  letter-spacing: 2px;
  background: linear-gradient(90deg, #409eff, #9333ea);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.sub {
  color: #909399;
  margin-top: 6px;
  font-size: 13px;
}

.login-btn {
  width: 100%;
}

.login-tip {
  text-align: center;
  color: #909399;
  font-size: 12px;
  margin-top: -4px;
}
</style>
