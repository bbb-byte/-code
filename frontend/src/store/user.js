import { defineStore } from 'pinia'
import { clearAuth, getToken, resetAuthExpirationState } from '@/utils/auth'

export const useUserStore = defineStore('user', {
    state: () => ({
        token: getToken(),
        userInfo: JSON.parse(localStorage.getItem('userInfo') || '{}')
    }),

    getters: {
        isLoggedIn: (state) => !!state.token,
        isAdmin: (state) => state.userInfo.role === 'admin',
        username: (state) => state.userInfo.username || ''
    },

    actions: {
        setToken(token) {
            this.token = token
            localStorage.setItem('token', token)
            resetAuthExpirationState()
        },

        setUserInfo(info) {
            this.userInfo = info
            localStorage.setItem('userInfo', JSON.stringify(info))
        },

        login(data) {
            this.setToken(data.token)
            this.setUserInfo({
                userId: data.userId,
                username: data.username,
                realName: data.realName,
                avatar: data.avatar,
                role: data.role
            })
        },

        logout() {
            this.token = ''
            this.userInfo = {}
            clearAuth()
            resetAuthExpirationState()
        }
    }
})
