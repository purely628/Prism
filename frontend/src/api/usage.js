import request from '../utils/request'

export const getUsageSummary = (days = 7) => request.get('/api/usage/summary', { params: { days } })

export const getUsagePage = (params) => request.get('/api/usage/page', { params })

export const getUsageQuota = (appId) => request.get('/api/usage/quota', { params: { appId } })
