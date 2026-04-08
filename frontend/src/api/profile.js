import http from '@/utils/http'

// 计算所有用户RFM值
export function calculateRFM() {
    return http.post('/profile/calculate-rfm')
}

// 执行K-Means聚类
export function performClustering(k = 5) {
    return http.post('/profile/kmeans-clustering', null, { params: { k } })
}

// 获取用户分群分布
export function getGroupDistribution() {
    return http.get('/profile/group-distribution')
}

// 获取聚类分布
export function getClusterDistribution() {
    return http.get('/profile/cluster-distribution')
}

// 获取RFM评分分布
export function getRFMDistribution() {
    return http.get('/profile/rfm-distribution')
}

// 获取聚类中心
export function getClusterCenters() {
    return http.get('/profile/cluster-centers')
}

// 获取高价值用户
export function getHighValueUsers(limit = 20) {
    return http.get('/profile/high-value-users', { params: { limit } })
}

// 获取TOP用户（支持按分群筛选）
export function getTopUsers(group = 'all', limit = 20) {
    return http.get('/profile/top-users', { params: { group, limit } })
}

// 获取单个用户画像
export function getUserProfile(userId) {
    return http.get(`/profile/user/${userId}`)
}
