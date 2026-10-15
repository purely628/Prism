/**
 * 数字/Key 等展示格式化与剪贴板工具
 */

export function formatNumber(n) {
  const v = Number(n)
  if (!Number.isFinite(v)) return '0'
  return v.toLocaleString('zh-CN')
}

/** token 数中文缩写：亿 / 万 */
export function formatTokens(n) {
  const v = Number(n) || 0
  if (v >= 1e8) return `${(v / 1e8).toFixed(2)} 亿`
  if (v >= 1e4) return `${(v / 1e4).toFixed(2)} 万`
  return v.toLocaleString('zh-CN')
}

/** appKey 脱敏：sk-prism-xxxx****abcd */
export function maskKey(key) {
  if (!key) return ''
  if (key.length <= 14) return key.slice(0, 4) + '****'
  return `${key.slice(0, 10)}****${key.slice(-4)}`
}

/** 缓存命中率：兼容 0~1 小数与已是百分比的两种返回 */
export function toPercent(rate) {
  const v = Number(rate) || 0
  return v > 0 && v <= 1 ? v * 100 : v
}

export function formatDateTime(val) {
  if (!val) return '-'
  const d = new Date(val)
  if (Number.isNaN(d.getTime())) return String(val)
  const p = (x) => String(x).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

export function nowTime() {
  return new Date().toLocaleTimeString('zh-CN', { hour12: false })
}

/** 复制文本到剪贴板（优先 navigator.clipboard，降级 execCommand） */
export async function copyText(text) {
  if (!text) return false
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    /* 降级处理 */
  }
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch {
    return false
  }
}
