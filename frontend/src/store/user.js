import { defineStore } from 'pinia'

export const useUserStore = defineStore('user', {
    state: () => ({
        token: localStorage.getItem('token') || '',
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
            localStorage.removeItem('token')
            localStorage.removeItem('userInfo')
        }
    }
})
