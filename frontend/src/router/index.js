import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/Login.vue'),
    meta: { title: '登录' }
  },
  {
    path: '/',
    component: () => import('../components/Layout.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', name: 'dashboard', component: () => import('../views/Dashboard.vue'), meta: { title: '仪表盘' } },
      { path: 'apps', name: 'apps', component: () => import('../views/Apps.vue'), meta: { title: '应用管理' } },
      { path: 'channels', name: 'channels', component: () => import('../views/Channels.vue'), meta: { title: '模型渠道' } },
      { path: 'usage', name: 'usage', component: () => import('../views/Usage.vue'), meta: { title: '用量统计' } },
      { path: 'kb', name: 'kb', component: () => import('../views/Kb.vue'), meta: { title: '知识库' } },
      { path: 'rag', name: 'rag', component: () => import('../views/Rag.vue'), meta: { title: 'RAG 对话' } },
      { path: 'playground', name: 'playground', component: () => import('../views/Playground.vue'), meta: { title: 'Playground' } },
      { path: 'agent', name: 'agent', component: () => import('../views/Agent.vue'), meta: { title: 'Agent 实验场' } }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'notFound',
    component: () => import('../views/NotFound.vue'),
    meta: { title: '404' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 登录守卫：无 token 一律去 /login
router.beforeEach((to) => {
  const token = localStorage.getItem('prism_token')
  if (!token && to.path !== '/login') {
    return { path: '/login', query: to.fullPath !== '/' ? { redirect: to.fullPath } : {} }
  }
  if (token && to.path === '/login') {
    return '/'
  }
  return true
})

router.afterEach((to) => {
  document.title = to.meta?.title ? `${to.meta.title} - Prism 控制台` : 'Prism 控制台'
})

export default router
