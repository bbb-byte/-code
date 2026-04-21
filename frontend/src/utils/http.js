import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { getToken, handleAuthExpired } from '@/utils/auth'

// 创建axios实例
const http = axios.create({
    baseURL: '/api',
    timeout: 600000,
    headers: {
        'Content-Type': 'application/json'
    }
})

// 请求拦截器
http.interceptors.request.use(
    config => {
        const token = getToken()
        if (token) {
            config.headers['Authorization'] = `Bearer ${token}`
        }
        return config
    },
    error => {
        return Promise.reject(error)
    }
)

// 将 HTTP 状态码转为用户友好的中文提示
function friendlyHttpError(status) {
    const map = {
        400: '请求参数有误，请检查后重试',
        401: '登录已过期，请重新登录',
        403: '您没有权限执行该操作',
        404: '请求的资源不存在',
        408: '请求超时，请稍后重试',
        500: '服务器内部错误，请联系管理员',
        502: '服务暂时不可用，请稍后重试',
        503: '服务正在维护中，请稍后重试',
        504: '服务器响应超时，请稍后重试',
    }
    return map[status] || `服务异常（错误码 ${status}），请稍后重试`
}

// 响应拦截器
http.interceptors.response.use(
    response => {
        const res = response.data

        // 根据响应码处理
        if (res.code === 200) {
            return res
        } else if (res.code === 401) {
            handleAuthExpired(router)
            return Promise.reject(new Error(res.message))
        } else if (res.code === 403) {
            ElMessage.error('您没有权限执行该操作')
            return Promise.reject(new Error(res.message || '无权限'))
        } else {
            ElMessage.error(res.message || '操作失败，请稍后重试')
            return Promise.reject(new Error(res.message))
        }
    },
    error => {
        const status = error.response?.status

        if (status === 401) {
            handleAuthExpired(router)
            return Promise.reject(error)
        }

        const msg = friendlyHttpError(status)
        ElMessage.error(msg)
        return Promise.reject(error)
    }
)

export default http
