import { defineStore } from 'pinia'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('prism_token') || '',
    nickname: localStorage.getItem('prism_nickname') || '',
    username: localStorage.getItem('prism_username') || ''
  }),
  actions: {
    setAuth({ token, nickname = '', username = '' }) {
      this.token = token
      this.nickname = nickname || username
      this.username = username
      localStorage.setItem('prism_token', token)
      localStorage.setItem('prism_nickname', this.nickname)
      localStorage.setItem('prism_username', username)
    },
    logout() {
      this.token = ''
      this.nickname = ''
      this.username = ''
      localStorage.removeItem('prism_token')
      localStorage.removeItem('prism_nickname')
      localStorage.removeItem('prism_username')
    }
  }
})
