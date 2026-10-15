<template>
  <el-container class="layout">
    <el-aside :width="isCollapse ? '64px' : '220px'" class="layout-aside">
      <div class="brand">
        <span class="brand-mark">P</span>
        <transition name="fade">
          <span v-if="!isCollapse" class="brand-name">Prism 控制台</span>
        </transition>
      </div>
      <el-menu
        :default-active="activeMenu"
        router
        :collapse="isCollapse"
        :collapse-transition="false"
        background-color="#001529"
        text-color="#a6adb4"
        active-text-color="#ffffff"
        class="layout-menu"
      >
        <el-menu-item v-for="item in menus" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <template #title>{{ item.title }}</template>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="layout-header">
        <div class="header-left">
          <el-icon class="collapse-btn" @click="isCollapse = !isCollapse">
            <Expand v-if="isCollapse" />
            <Fold v-else />
          </el-icon>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item>Prism</el-breadcrumb-item>
            <el-breadcrumb-item>{{ pageTitle }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="header-right">
          <el-dropdown @command="handleCommand">
            <span class="user-info">
              <el-icon class="user-avatar"><UserFilled /></el-icon>
              <span class="user-name">{{ userStore.nickname || userStore.username || '用户' }}</span>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>
                  <el-icon><User /></el-icon>{{ userStore.username || '-' }}
                </el-dropdown-item>
                <el-dropdown-item divided command="logout">
                  <el-icon><SwitchButton /></el-icon>退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="layout-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '../stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const isCollapse = ref(false)

const menus = [
  { path: '/dashboard', title: '仪表盘', icon: 'Odometer' },
  { path: '/apps', title: '应用管理', icon: 'Key' },
  { path: '/channels', title: '模型渠道', icon: 'Connection' },
  { path: '/usage', title: '用量统计', icon: 'TrendCharts' },
  { path: '/kb', title: '知识库', icon: 'Notebook' },
  { path: '/rag', title: 'RAG 对话', icon: 'ChatDotRound' },
  { path: '/playground', title: 'Playground', icon: 'MagicStick' },
  { path: '/agent', title: 'Agent 实验场', icon: 'Cpu' }
]

const activeMenu = computed(() => route.path)
const pageTitle = computed(() => route.meta?.title || '')

function handleCommand(cmd) {
  if (cmd === 'logout') {
    ElMessageBox.confirm('确定退出登录吗？', '提示', { type: 'warning', confirmButtonText: '退出', cancelButtonText: '取消' })
      .then(() => {
        userStore.logout()
        router.push('/login')
      })
      .catch(() => {})
  }
}
</script>

<style scoped>
.layout {
  height: 100%;
}

.layout-aside {
  background-color: #001529;
  transition: width 0.2s;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.brand {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: #fff;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  flex-shrink: 0;
}

.brand-mark {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: linear-gradient(135deg, #409eff, #9333ea);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-weight: 800;
  font-size: 16px;
}

.brand-name {
  font-size: 16px;
  font-weight: 600;
  white-space: nowrap;
}

.layout-menu {
  border-right: none;
  flex: 1;
  overflow-y: auto;
}

.layout-menu :deep(.el-menu-item.is-active) {
  background-color: #409eff !important;
}

.layout-menu :deep(.el-menu-item:hover) {
  background-color: rgba(255, 255, 255, 0.06);
}

.layout-header {
  height: 60px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.collapse-btn {
  font-size: 20px;
  cursor: pointer;
  color: #606266;
}

.collapse-btn:hover {
  color: #409eff;
}

.header-right .user-info {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  color: #303133;
  outline: none;
}

.user-avatar {
  font-size: 20px;
  color: #409eff;
}

.user-name {
  font-size: 14px;
}

.layout-main {
  background-color: #f0f2f5;
  overflow-y: auto;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
