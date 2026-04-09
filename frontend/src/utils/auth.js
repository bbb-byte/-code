import { ElMessage } from 'element-plus'

const TOKEN_KEY = 'token'
const USER_INFO_KEY = 'userInfo'

let hasHandledAuthExpiration = false

export function getToken() {
    return localStorage.getItem(TOKEN_KEY) || ''
}

export function clearAuth() {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_INFO_KEY)
}

export function getUserInfo() {
    try {
        return JSON.parse(localStorage.getItem(USER_INFO_KEY) || '{}')
    } catch (error) {
        console.error('解析用户信息失败:', error)
        return {}
    }
}

export function isAdminUser() {
    return getUserInfo().role === 'admin'
}

export function resetAuthExpirationState() {
    hasHandledAuthExpiration = false
}

function decodeBase64Url(value) {
    const normalized = value.replace(/-/g, '+').replace(/_/g, '/')
    const padded = normalized.padEnd(normalized.length + ((4 - normalized.length % 4) % 4), '=')
    const binary = atob(padded)
    const bytes = Uint8Array.from(binary, char => char.charCodeAt(0))

    return new TextDecoder().decode(bytes)
}

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
