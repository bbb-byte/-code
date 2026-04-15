import http from '@/utils/http'

/**
 * 获取首页仪表盘聚合数据。
 */
export function getDashboard() {
    return http.get('/analysis/dashboard')
}

/**
 * 获取行为类型分布。
 */
export function getBehaviorDistribution() {
    return http.get('/analysis/behavior-distribution')
}

/**
 * 获取按日期聚合的行为趋势；时间参数为空时由后端回退到最新区间。
 */
export function getDailyTrend(startDate, endDate) {
    const params = {}
    if (startDate) params.startDate = startDate
    if (endDate) params.endDate = endDate
    return http.get('/analysis/daily-trend', { params })
}

/**
 * 获取按浏览量排序的热门商品。
 */
export function getHotProductsByView(limit = 10) {
    return http.get('/analysis/hot-products/view', { params: { limit } })
}

/**
 * 获取按购买量排序的热门商品。
 */
export function getHotProductsByBuy(limit = 10) {
    return http.get('/analysis/hot-products/buy', { params: { limit } })
}

/**
 * 获取热门商品及其公网补充指标的分页数据。
 */
export function getHotProductsWithPublicMetrics(page = 1, pageSize = 10, onlyWithMetrics = true) {
    return http.get('/analysis/hot-products/public-metrics', { params: { page, pageSize, onlyWithMetrics } })
}

/**
 * 获取热门类目分布。
 */
export function getHotCategories(limit = 10) {
    return http.get('/analysis/hot-categories', { params: { limit } })
}

/**
 * 获取浏览到购买的漏斗统计。
 */
export function getConversionFunnel() {
    return http.get('/analysis/conversion-funnel')
}

/**
 * 获取每小时行为分布。
 */
export function getHourlyDistribution() {
    return http.get('/analysis/hourly-distribution')
}

/**
 * 获取全局概览指标。
 */
export function getOverview() {
    return http.get('/analysis/overview')
}
