import { ElMessage } from 'element-plus'

const TOKEN_KEY = 'token'
const USER_INFO_KEY = 'userInfo'

let hasHandledAuthExpiration = false

/**
 * 读取本地存储中的 JWT。
 */
export function getToken() {
    return localStorage.getItem(TOKEN_KEY) || ''
}

/**
 * 清理本地登录态。
 */
export function clearAuth() {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_INFO_KEY)
}

/**
 * 读取并解析本地存储中的用户信息。
 */
export function getUserInfo() {
    try {
        return JSON.parse(localStorage.getItem(USER_INFO_KEY) || '{}')
    } catch (error) {
        console.error('解析用户信息失败:', error)
        return {}
    }
}

/**
 * 判断当前登录用户是否为管理员。
 */
export function isAdminUser() {
    return getUserInfo().role === 'admin'
}

/**
 * 重置“登录过期已处理”标记，避免新的会话被旧状态影响。
 */
export function resetAuthExpirationState() {
    hasHandledAuthExpiration = false
}

/**
 * 解码 JWT 中使用的 Base64URL 字符串。
 */
function decodeBase64Url(value) {
    const normalized = value.replace(/-/g, '+').replace(/_/g, '/')
    const padded = normalized.padEnd(normalized.length + ((4 - normalized.length % 4) % 4), '=')
    const binary = atob(padded)
    const bytes = Uint8Array.from(binary, char => char.charCodeAt(0))

    return new TextDecoder().decode(bytes)
}

/**
 * 解析 JWT payload，提取过期时间、角色等字段。
 */
export function parseTokenPayload(token = getToken()) {
    if (!token) {
        return null
    }

    try {
        const [, payload] = token.split('.')
        if (!payload) {
            return null
        }

        return JSON.parse(decodeBase64Url(payload))
    } catch (error) {
        console.error('解析Token失败:', error)
        return null
    }
}

/**
 * 判断 Token 是否已过期。
 */
export function isTokenExpired(token = getToken()) {
    if (!token) {
        return true
    }

    const payload = parseTokenPayload(token)
    if (!payload) {
        return true
    }

    if (!payload.exp) {
        return false
    }

    return payload.exp * 1000 <= Date.now()
}

/**
 * 统一处理登录过期：清理状态、弹提示并跳转登录页。
 */
export function handleAuthExpired(router, options = {}) {
    const {
        message = '登录已过期，请重新登录',
        redirect = router.currentRoute.value?.fullPath,
        notify = true
    } = options

    clearAuth()

    if (notify && !hasHandledAuthExpiration) {
        ElMessage.error(message)
    }

    hasHandledAuthExpiration = true

    const isOnLoginPage = router.currentRoute.value?.path === '/login'
    if (!isOnLoginPage) {
        const query = redirect && redirect !== '/login' ? { redirect } : undefined
        router.replace({ path: '/login', query })
    }
}
