import http from '@/utils/http'

/**
 * 启动 CSV 导入任务。
 */
export function importData(filePath, batchSize = 5000, maxRows = 0) {
    return http.post('/data/import', null, {
        params: { filePath, batchSize, maxRows }
    })
}

/**
 * 获取导入任务进度。
 */
export function getImportProgress() {
    return http.get('/data/import/progress')
}

/**
 * 请求停止当前导入任务。
 */
export function stopImport() {
    return http.post('/data/import/stop')
}

/**
 * 触发完整的数据分析流程。
 */
export function analyzeData(clusterK = 5) {
    return http.post('/data/analyze', null, { params: { clusterK } })
}

/**
 * 启动公网满意度抓取任务。
 */
export function crawlData(mappingPath, outputDir, fixtureDir) {
    return http.post('/data/crawl', null, {
        params: { mappingPath, outputDir, fixtureDir }
    })
}

/**
 * 复用当前浏览器搜索页抓取公网指标。
 */
export function crawlAttachedSearchData(candidatePath, outputPath, cdpUrl = 'http://127.0.0.1:9222') {
    return http.post('/data/crawl-attached-search', null, {
        params: { candidatePath, outputPath, cdpUrl }
    })
}

/**
 * 召回公网映射候选商品；可选先从原始行为文件自动生成内部商品快照。
 */
export function recallPublicMappingCandidates(productPath, outputPath, fixtureDir, sourceDataPath = '', generatedProductPath = '', topK = 5, maxProducts = 50) {
    return http.post('/data/public-mapping/recall', null, {
        params: { productPath, outputPath, fixtureDir, sourceDataPath, generatedProductPath, topK, maxProducts }
    })
}

/**
 * 对已召回的候选商品计算匹配分数。
 */
export function scorePublicMappingCandidates(productPath, candidatePath, outputPath) {
    return http.post('/data/public-mapping/score', null, {
        params: { productPath, candidatePath, outputPath }
    })
}

/**
 * 分页预览候选评分结果。
 */
export function previewPublicMappingScores(scorePath, page = 1, pageSize = 50) {
    return http.get('/data/public-mapping/score-preview', {
        params: { scorePath, page, pageSize }
    })
}

/**
 * 批量确认人工审核通过的公网映射。
 */
export function confirmPublicMappings(rows) {
    return http.post('/data/public-mapping/confirm', {
        rows
    })
}

/**
 * 获取最近确认过的公网映射。
 */
export function getLatestPublicMappings(sourcePlatform = 'jd', limit = 10) {
    return http.get('/data/public-mapping/latest', {
        params: { sourcePlatform, limit }
    })
}

/**
 * 撤销一条公网映射。
 */
export function removePublicMapping(id) {
    return http.post('/data/public-mapping/remove', null, {
        params: { id }
    })
}

/**
 * 获取公网后台任务的进度。
 */
export function getPublicTaskProgress(taskId) {
    return http.get('/data/public-task/progress', {
        params: { taskId }
    })
}

/**
 * 取消公网后台任务。
 */
export function cancelPublicTask(taskId) {
    return http.post('/data/public-task/cancel', null, {
        params: { taskId }
    })
}

/**
 * 获取最近导入的原始行为记录。
 */
export function getLatestBehaviors(limit = 10) {
    return http.get('/data/latest', { params: { limit } })
}
