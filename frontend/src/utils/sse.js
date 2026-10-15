/**
 * 以 POST 方式发起 SSE 请求，用 fetch + ReadableStream 手工解析。
 * （EventSource 只支持 GET 且无法带 Authorization 头，故自己实现）
 *
 * 兼容两种协议：
 * 1. 命名事件：`event: chunk\ndata: {"delta":".."}`，事件名取 event: 行
 * 2. OpenAI 格式：无 event: 行 → 事件名默认 "message"；`data: [DONE]` 表示流结束
 *
 * data 内容能被 JSON.parse 就解析为对象，否则保持原始字符串。
 *
 * @param {string} url
 * @param {object} body      JSON 请求体
 * @param {object} options
 * @param {(name: string, data: any) => void} options.onEvent
 * @param {() => void} [options.onDone]    流正常结束（含 [DONE] / 连接关闭 / 主动中止）
 * @param {(err: Error) => void} [options.onError]
 * @param {Record<string, string>} [options.headers] 额外请求头（如 Authorization）
 * @param {AbortSignal} [options.signal]   取消信号
 */
export async function ssePost(url, body, options = {}) {
  const { onEvent, onDone, onError, headers = {}, signal } = options

  // 解析一个事件块，返回是否收到结束标记 [DONE]
  const dispatchBlock = (block) => {
    const lines = block.split(/\r?\n/)
    let eventName = ''
    const dataLines = []
    for (const line of lines) {
      if (!line || line.startsWith(':')) continue // 空行/注释行
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).replace(/^ /, ''))
      }
    }
    if (dataLines.length === 0) return false
    const dataStr = dataLines.join('\n')
    if (dataStr.trim() === '[DONE]') return true
    let payload = dataStr
    try {
      payload = JSON.parse(dataStr)
    } catch {
      /* 非 JSON 保持原字符串 */
    }
    if (onEvent) onEvent(eventName || 'message', payload)
    return false
  }

  let resp
  try {
    resp = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json;charset=utf-8',
        Accept: 'text/event-stream',
        ...headers
      },
      body: JSON.stringify(body),
      signal
    })
  } catch (e) {
    if (e && e.name === 'AbortError') {
      if (onDone) onDone()
      return
    }
    if (onError) onError(e instanceof Error ? e : new Error(String(e)))
    return
  }

  if (!resp.ok) {
    let message = `请求失败（HTTP ${resp.status}）`
    try {
      const j = await resp.json()
      message = j?.error?.message || j?.message || message
    } catch {
      /* 非 JSON 错误体 */
    }
    if (onError) onError(new Error(message))
    return
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let finished = false

  const consumeBuffer = (flush) => {
    const parts = buffer.split(/\r\n\r\n|\n\n/)
    buffer = flush ? '' : parts.pop() || ''
    for (const block of parts) {
      if (!block.trim()) continue
      if (dispatchBlock(block)) {
        finished = true
        return
      }
    }
  }

  try {
    while (!finished) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      consumeBuffer(false)
    }
    if (!finished && buffer.trim()) {
      // 有些服务流结束时不带末尾空行，兜底冲刷一次
      consumeBuffer(true)
    }
  } catch (e) {
    if (e && e.name === 'AbortError') {
      if (onDone) onDone()
      return
    }
    if (onError) onError(e instanceof Error ? e : new Error(String(e)))
    return
  }

  if (onDone) onDone()
}
