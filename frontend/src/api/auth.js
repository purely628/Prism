import request from '../utils/request'

export const login = (data) => request.post('/api/auth/login', data)

export const getMe = () => request.get('/api/auth/me')
