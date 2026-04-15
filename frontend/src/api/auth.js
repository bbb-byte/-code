import http from '@/utils/http'

/**
 * 提交登录表单，换取后端签发的 JWT 与用户信息。
 */
export function login(data) {
    return http.post('/auth/login', data)
}

/**
 * 提交注册信息，创建普通用户账号。
 */
export function register(data) {
    return http.post('/auth/register', data)
}

/**
 * 检查用户名是否可用，供注册页实时校验。
 */
export function checkUsername(username) {
    return http.get('/auth/check-username', { params: { username } })
}
