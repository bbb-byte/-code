import http from '@/utils/http'

/**
 * 分页查询用户列表。
 */
export function getUserPage(params) {
    return http.get('/user/page', { params })
}

/**
 * 获取单个用户详情。
 */
export function getUserById(id) {
    return http.get(`/user/${id}`)
}

/**
 * 创建用户。
 */
export function createUser(data) {
    return http.post('/user', data)
}

/**
 * 更新用户资料。
 */
export function updateUser(id, data) {
    return http.put(`/user/${id}`, data)
}

/**
 * 删除用户。
 */
export function deleteUser(id) {
    return http.delete(`/user/${id}`)
}

/**
 * 单独更新用户启用状态。
 */
export function updateUserStatus(id, status) {
    return http.put(`/user/${id}/status`, null, { params: { status } })
}
