import request from '../utils/request'

export const listKbs = () => request.get('/api/kb')

export const createKb = (data) => request.post('/api/kb', data)

export const updateKb = (id, data) => request.put(`/api/kb/${id}`, data)

export const deleteKb = (id) => request.delete(`/api/kb/${id}`)

export const listDocs = (kbId) => request.get(`/api/kb/${kbId}/documents`)

/** multipart 上传文档（拦截器自动附加 Bearer） */
export function uploadDoc(kbId, file) {
  const fd = new FormData()
  fd.append('file', file)
  return request.post(`/api/kb/${kbId}/documents`, fd, { timeout: 120000 })
}

export const deleteDoc = (kbId, docId) => request.delete(`/api/kb/${kbId}/documents/${docId}`)

export const retrieve = (kbId, data) => request.post(`/api/kb/${kbId}/retrieve`, data)
