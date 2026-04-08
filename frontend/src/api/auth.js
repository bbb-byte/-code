import http from '@/utils/http'

// 登录
export function login(data) {
    return http.post('/auth/login', data)
}

// 注册
export function register(data) {
    return http.post('/auth/register', data)
}

// 检查用户名
export function checkUsername(username) {
    return http.get('/auth/check-username', { params: { username } })
}
