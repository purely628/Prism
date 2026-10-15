import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

const request = axios.create({
  timeout: 30000,
  headers: { 'Content-Type': 'application/json;charset=utf-8' }
})

// 请求拦截：附加 JWT
request.interceptors.request.use((config) => {
  const token = localStorage.getItem('prism_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  // FormData 上传：删掉全局 JSON Content-Type，让浏览器自动生成 multipart boundary
  // （否则后端收到 application/json 会报 "not a multipart request"）
  if (config.data instanceof FormData) {
    delete config.headers['Content-Type']
  }
  return config
})

// 响应拦截：统一处理 Result{code,message,data}；401 清 token 跳登录
request.interceptors.response.use(
  (response) => {
    const res = response.data
    // /api/** 统一返回 Result，这里直接剥壳返回 data
    if (res !== null && typeof res === 'object' && 'code' in res) {
      if (res.code !== 0) {
        const msg = res.message || '请求失败'
        ElMessage.error(msg)
        return Promise.reject(new Error(msg))
      }
      return res.data
    }
    return res
  },
  (error) => {
    const status = error.response?.status
    if (status === 401) {
      localStorage.removeItem('prism_token')
      localStorage.removeItem('prism_nickname')
      localStorage.removeItem('prism_username')
      if (router.currentRoute.value.path !== '/login') {
        ElMessage.error('登录已过期，请重新登录')
        router.push('/login')
      }
    } else {
      ElMessage.error(error.response?.data?.message || error.message || '网络异常，请稍后重试')
    }
    return Promise.reject(error)
  }
)

export default request
