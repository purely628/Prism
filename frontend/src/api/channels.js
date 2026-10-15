import request from '../utils/request'

export const listChannels = () => request.get('/api/channels')

export const createChannel = (data) => request.post('/api/channels', data)

export const updateChannel = (id, data) => request.put(`/api/channels/${id}`, data)

export const setChannelStatus = (id, status) => request.post(`/api/channels/${id}/status`, { status })
