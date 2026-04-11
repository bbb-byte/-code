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
export function crawlData(mappingPath, outputDir, fixtureDir) {
    return http.post('/data/crawl', null, {
        params: { mappingPath, outputDir, fixtureDir }
    })
}

export function crawlAttachedSearchData(candidatePath, outputPath, cdpUrl = 'http://127.0.0.1:9222') {
    return http.post('/data/crawl-attached-search', null, {
        params: { candidatePath, outputPath, cdpUrl }
    })
}

// 召回公网映射候选商品
export function recallPublicMappingCandidates(productPath, outputPath, fixtureDir, sourceDataPath = '', generatedProductPath = '', topK = 5, maxProducts = 50) {
    return http.post('/data/public-mapping/recall', null, {
        params: { productPath, outputPath, fixtureDir, sourceDataPath, generatedProductPath, topK, maxProducts }
    })
}

// 计算公网映射候选分数
export function scorePublicMappingCandidates(productPath, candidatePath, outputPath) {
    return http.post('/data/public-mapping/score', null, {
        params: { productPath, candidatePath, outputPath }
    })
}

// 预览公网映射评分结果
export function previewPublicMappingScores(scorePath, page = 1, pageSize = 50) {
    return http.get('/data/public-mapping/score-preview', {
        params: { scorePath, page, pageSize }
    })
}

// 确认公网映射并入库
export function confirmPublicMappings(rows) {
    return http.post('/data/public-mapping/confirm', {
        rows
    })
}

// 获取最近确认的公网映射
export function getLatestPublicMappings(sourcePlatform = 'jd', limit = 10) {
    return http.get('/data/public-mapping/latest', {
        params: { sourcePlatform, limit }
    })
}

// 撤销公网映射
export function removePublicMapping(id) {
    return http.post('/data/public-mapping/remove', null, {
        params: { id }
    })
}

// 获取公网任务进度
export function getPublicTaskProgress(taskId) {
    return http.get('/data/public-task/progress', {
        params: { taskId }
    })
}

// 取消公网任务
export function cancelPublicTask(taskId) {
    return http.post('/data/public-task/cancel', null, {
        params: { taskId }
    })
}

// 获取最新行为记录
export function getLatestBehaviors(limit = 10) {
    return http.get('/data/latest', { params: { limit } })
}
