<template>
  <div class="not-found">
    <div class="code">404</div>
    <div class="text">页面不存在或已被移除</div>
    <el-button type="primary" @click="goHome">返回仪表盘</el-button>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'

const router = useRouter()
function goHome() {
  router.push('/dashboard')
}
</script>

<style scoped>
.not-found {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  background: #f0f2f5;
}

.code {
  font-size: 96px;
  font-weight: 800;
  background: linear-gradient(90deg, #409eff, #9333ea);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
  line-height: 1;
}

.text {
  color: #909399;
}
</style>
