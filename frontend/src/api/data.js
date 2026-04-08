import http from '@/utils/http'

// 导入CSV数据
export function importData(filePath, batchSize = 5000, maxRows = 0) {
    return http.post('/data/import', null, {
        params: { filePath, batchSize, maxRows }
    })
}

// 获取导入进度
export function getImportProgress() {
    return http.get('/data/import/progress')
}

// 停止导入
export function stopImport() {
    return http.post('/data/import/stop')
}

// 执行完整数据分析
export function analyzeData(clusterK = 5) {
    return http.post('/data/analyze', null, { params: { clusterK } })
}

// 执行数据爬取
export function crawlData() {
    return http.post('/data/crawl')
}

// 获取最新行为记录
export function getLatestBehaviors(limit = 10) {
    return http.get('/data/latest', { params: { limit } })
}
