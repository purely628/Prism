import request from '../utils/request'

export const listApps = () => request.get('/api/apps')

export const createApp = (data) => request.post('/api/apps', data)

export const resetAppKey = (id) => request.post(`/api/apps/${id}/resetKey`)

export const setAppStatus = (id, status) => request.post(`/api/apps/${id}/status`, { status })

export const setAppQuota = (id, monthlyQuota) => request.post(`/api/apps/${id}/quota`, { monthlyQuota })

export const deleteApp = (id) => request.delete(`/api/apps/${id}`)
