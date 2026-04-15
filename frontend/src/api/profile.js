import http from '@/utils/http'

/**
 * 触发全量 RFM 计算。
 */
export function calculateRFM() {
    return http.post('/profile/calculate-rfm')
}

/**
 * 触发 K-Means 聚类。
 */
export function performClustering(k = 5) {
    return http.post('/profile/kmeans-clustering', null, { params: { k } })
}

/**
 * 获取用户分群分布。
 */
export function getGroupDistribution() {
    return http.get('/profile/group-distribution')
}

/**
 * 获取聚类分布。
 */
export function getClusterDistribution() {
    return http.get('/profile/cluster-distribution')
}

/**
 * 获取 RFM 得分分布。
 */
export function getRFMDistribution() {
    return http.get('/profile/rfm-distribution')
}

/**
 * 获取聚类中心点。
 */
export function getClusterCenters() {
    return http.get('/profile/cluster-centers')
}

/**
 * 获取高价值用户列表。
 */
export function getHighValueUsers(limit = 20) {
    return http.get('/profile/high-value-users', { params: { limit } })
}

/**
 * 获取指定分群下的 TOP 用户。
 */
export function getTopUsers(group = 'all', limit = 20) {
    return http.get('/profile/top-users', { params: { group, limit } })
}

/**
 * 获取单个用户画像详情。
 */
export function getUserProfile(userId) {
    return http.get(`/profile/user/${userId}`)
}
