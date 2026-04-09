import http from '@/utils/http'

// 获取仪表盘数据
export function getDashboard() {
    return http.get('/analysis/dashboard')
}

// 获取行为类型分布
export function getBehaviorDistribution() {
    return http.get('/analysis/behavior-distribution')
}

// 获取每日行为趋势
export function getDailyTrend(startDate, endDate) {
    const params = {}
    if (startDate) params.startDate = startDate
    if (endDate) params.endDate = endDate
    return http.get('/analysis/daily-trend', { params })
}

// 获取热门商品(按浏览量)
export function getHotProductsByView(limit = 10) {
    return http.get('/analysis/hot-products/view', { params: { limit } })
}

// 获取热门商品(按购买量)
export function getHotProductsByBuy(limit = 10) {
    return http.get('/analysis/hot-products/buy', { params: { limit } })
}

// 获取热门商品及京东公开评价补充指标
export function getHotProductsWithPublicMetrics(limit = 10) {
    return http.get('/analysis/hot-products/public-metrics', { params: { limit } })
}

// 获取热门类目
export function getHotCategories(limit = 10) {
    return http.get('/analysis/hot-categories', { params: { limit } })
}

// 获取转化漏斗数据
export function getConversionFunnel() {
    return http.get('/analysis/conversion-funnel')
}

// 获取每小时行为分布
export function getHourlyDistribution() {
    return http.get('/analysis/hourly-distribution')
}

// 获取数据概览
export function getOverview() {
    return http.get('/analysis/overview')
}
