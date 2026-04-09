import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { getToken, handleAuthExpired } from '@/utils/auth'

// 创建axios实例
const http = axios.create({
    baseURL: '/api',
    timeout: 60000,
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
            ElMessage.error(res.message || '无权限访问')
            return Promise.reject(new Error(res.message || '无权限访问'))
        } else {
            ElMessage.error(res.message || '请求失败')
            return Promise.reject(new Error(res.message))
        }
    },
    error => {
        console.error('请求错误:', error)
        const status = error.response?.status

        if (status === 401) {
            handleAuthExpired(router)
            return Promise.reject(error)
        }

        if (status === 403) {
            ElMessage.error(error.response?.data?.message || '无权限访问')
            return Promise.reject(error)
        }

        ElMessage.error(error.message || '网络错误')
        return Promise.reject(error)
    }
)

export default http
