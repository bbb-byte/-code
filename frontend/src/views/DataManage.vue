<template>
  <div class="data-manage">
    <div class="page-header">
      <h2 class="page-title">数据管理</h2>
      <p class="page-desc">数据采集、导入与预处理中心</p>
    </div>

    <el-row :gutter="24">
      <!-- 数据爬取 -->
      <el-col :xs="24" :md="8">
        <div class="card">
          <div class="card-title">数据爬取</div>
          <div class="crawler-ui">
            <p class="desc">使用 Python 爬虫从外部电商平台抓取实时用户行为数据，增加数据源多样性。</p>
            <div class="crawler-actions">
              <el-button type="warning" @click="handleCrawl" :loading="crawling" plain>
                <el-icon><Search /></el-icon> 开始爬取数据
              </el-button>
            </div>
            <div v-if="crawlResult" class="crawl-info">
              <el-alert
                title="爬取成功"
                :description="`数据地址: ${crawlResult.outputFile}`"
                type="success"
                show-icon
                :closable="false"
              />
              <el-button size="small" type="primary" link @click="useCrawledPath" class="mt-2">
                自动填写导入路径
              </el-button>
            </div>
          </div>
        </div>
      </el-col>

      <!-- 数据导入 -->
      <el-col :xs="24" :md="8">
        <div class="card">
          <div class="card-title">数据导入</div>
          <el-form :model="importForm" label-width="80px" label-position="left">
            <el-form-item label="文件路径">
              <el-input v-model="importForm.filePath" placeholder="CSV文件绝对路径" />
            </el-form-item>
            <el-form-item label="批量大小">
              <el-input-number v-model="importForm.batchSize" :min="1000" :max="10000" :step="1000" style="width: 100%" />
            </el-form-item>
            <el-form-item label="最大行数">
              <el-input-number v-model="importForm.maxRows" :min="0" :step="100000" style="width: 100%" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="handleImport" :loading="importing">
                <el-icon><Upload /></el-icon> 开始导入
              </el-button>
              <el-button @click="handleStopImport" :disabled="!importing">
                <el-icon><VideoPause /></el-icon> 停止
              </el-button>
            </el-form-item>
          </el-form>
          
          <div v-if="importing" class="import-progress">
            <div class="flex justify-between mb-2 text-sm text-gray-500">
              <span>导入进度</span>
              <span>{{ importProgress }}%</span>
            </div>
            <el-progress :percentage="importProgress" :stroke-width="8" :show-text="false" />
          </div>
        </div>
      </el-col>

      <!-- 数据分析 -->
      <el-col :xs="24" :md="8">
        <div class="card">
          <div class="card-title">数据分析</div>
          <el-form :model="analyzeForm" label-width="90px" label-position="left">
            <el-form-item label="聚类簇数K">
              <el-input-number v-model="analyzeForm.clusterK" :min="2" :max="10" />
            </el-form-item>
            <el-form-item>
              <el-button type="success" @click="handleAnalyze" :loading="analyzing">
                <el-icon><DataAnalysis /></el-icon> 执行分析
              </el-button>
            </el-form-item>
            <div class="analyze-tips">
              <p class="font-medium">分析流程：</p>
              <ol>
                <li>1. 计算用户 RFM 指标</li>
                <li>2. 基于 RFM 进行用户分群</li>
                <li>3. 执行 K-Means 聚类分析</li>
              </ol>
            </div>
          </el-form>
        </div>
      </el-col>
    </el-row>

    <!-- 数据概览 -->
    <div class="card">
      <div class="card-title">数据概览</div>
      <el-row :gutter="24">
        <el-col :xs="12" :sm="6">
          <div class="overview-item">
            <div class="label">用户总数</div>
            <div class="value">{{ overview.totalUsers.toLocaleString() }}</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="overview-item">
            <div class="label">商品总数</div>
            <div class="value">{{ overview.totalProducts.toLocaleString() }}</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="overview-item">
            <div class="label">类目总数</div>
            <div class="value">{{ overview.totalCategories.toLocaleString() }}</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="overview-item">
            <div class="label">行为记录</div>
            <div class="value">{{ overview.totalBehaviors.toLocaleString() }}</div>
          </div>
        </el-col>
      </el-row>
    </div>

    <!-- 最新入库数据 -->
    <div class="card">
      <div class="card-header flex justify-between items-center mb-4">
        <span class="card-title mb-0">最新入库数据预览</span>
        <el-button size="small" type="primary" link @click="loadLatestBehaviors">
          <el-icon><Refresh /></el-icon> 刷新
        </el-button>
      </div>
      <el-table :data="latestBehaviors" stripe style="width: 100%" v-loading="loadingLatest">
        <el-table-column prop="userId" label="用户ID" width="120" />
        <el-table-column prop="itemId" label="商品ID" width="120" />
        <el-table-column prop="categoryId" label="类目">
          <template #default="scope">
            <el-tag effect="plain" type="info">{{ formatCategory(scope.row.categoryId) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="behaviorType" label="行为类型" width="120">
          <template #default="scope">
            <el-tag :type="getTagType(scope.row.behaviorType)" effect="light">{{ scope.row.behaviorType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="behaviorDateTime" label="时间" />
      </el-table>
    </div>

    <!-- 操作日志 -->
    <div class="card">
      <div class="card-title">操作日志</div>
      <el-timeline>
        <el-timeline-item
          v-for="log in logs"
          :key="log.id"
          :timestamp="log.time"
          :type="log.type"
          :hollow="true"
        >
          {{ log.message }}
        </el-timeline-item>
      </el-timeline>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onUnmounted } from 'vue'
import { importData, getImportProgress, stopImport, analyzeData, crawlData, getLatestBehaviors } from '@/api/data'
import { getOverview } from '@/api/analysis'
import { ElMessage } from 'element-plus'
import { Upload, VideoPause, DataAnalysis, Search, Refresh } from '@element-plus/icons-vue'

const importing = ref(false)
const analyzing = ref(false)
const crawling = ref(false)
const loadingLatest = ref(false)
const importProgress = ref(0)
const crawlResult = ref(null)
const latestBehaviors = ref([])

const importForm = reactive({
  filePath: '/Users/leiminghao/Desktop/论文/code/UserBehavior.csv',
  batchSize: 5000,
  maxRows: 100000
})

const analyzeForm = reactive({
  clusterK: 5
})

const overview = ref({
  totalUsers: 0,
  totalProducts: 0,
  totalCategories: 0,
  totalBehaviors: 0
})

const logs = ref([
  { id: 1, time: new Date().toLocaleString(), message: '系统初始化完成', type: 'success' }
])

let progressTimer = null

const handleCrawl = async () => {
  crawling.value = true
  addLog('开始爬取数据 (调用 Python 爬虫)...', 'warning')
  
  try {
    const res = await crawlData()
    crawlResult.value = res.data
    const logMatch = res.data.log?.match(/共[获取]?\s*(\d+)\s*条/)
    const count = logMatch ? logMatch[1] : '部分'
    
    addLog(`✨ 数据爬取成功！获取了 ${count} 条实时交易行为数据。`, 'success')
    ElMessage.success(`成功采集 ${count} 条数据`)
  } catch (error) {
    addLog('采集失败: ' + (error.response?.data?.message || error.message), 'danger')
  } finally {
    crawling.value = false
  }
}

const useCrawledPath = () => {
  if (crawlResult.value) {
    importForm.filePath = crawlResult.value.outputFile
    ElMessage.success('已自动填写爬取的文件路径')
  }
}

const handleImport = async () => {
  if (!importForm.filePath) {
    ElMessage.warning('请输入数据文件路径')
    return
  }
  
  importing.value = true
  addLog(`开始从路径读取数据: ${importForm.filePath}`, 'primary')
  
  try {
    await importData(importForm.filePath, importForm.batchSize, importForm.maxRows)
    ElMessage.success('导入任务已启动')
    
    progressTimer = setInterval(async () => {
      try {
        const res = await getImportProgress()
        importProgress.value = Math.min(res.data.progress, 100)
        
        if (!res.data.importing) {
          clearInterval(progressTimer)
          importing.value = false
          importProgress.value = 100
          addLog('✅ 数据导入任务已成功执行完毕！', 'success')
          ElMessage.success('数据导入完成')
          loadOverview() 
          loadLatestBehaviors() 
        }
      } catch (e) {
        console.error(e)
      }
    }, 2000)
  } catch (error) {
    importing.value = false
    addLog('❌ 导入失败: ' + (error.response?.data?.message || error.message), 'danger')
  }
}

const handleStopImport = async () => {
  try {
    await stopImport()
    ElMessage.info('正在停止导入')
    addLog('用户停止导入', 'warning')
  } catch (error) {
    console.error(error)
  }
}

const handleAnalyze = async () => {
  analyzing.value = true
  addLog('开始执行数据分析...', 'primary')
  
  try {
    await analyzeData(analyzeForm.clusterK)
    ElMessage.success('数据分析完成')
    addLog('数据分析完成', 'success')
  } catch (error) {
    addLog('分析失败: ' + error.message, 'danger')
  } finally {
    analyzing.value = false
  }
}

const loadOverview = async () => {
  try {
    const res = await getOverview()
    overview.value = res.data
  } catch (error) {
    console.error(error)
  }
}

const loadLatestBehaviors = async () => {
  loadingLatest.value = true
  try {
    const res = await getLatestBehaviors(10)
    latestBehaviors.value = res.data
  } catch (error) {
    console.error(error)
  } finally {
    loadingLatest.value = false
  }
}

const getTagType = (type) => {
  const map = {
    'pv': 'info',
    'buy': 'success',
    'cart': 'warning',
    'fav': 'danger'
  }
  return map[type] || 'info'
}

const formatCategory = (id) => {
  const map = {
    1101: '📚 文学小说',
    1102: '🔬 科学技术',
    1103: '🏛 人文历史',
    1104: '💰 经济管理',
    1105: '🎨 艺术设计',
    1106: '🏃 生活百科'
  }
  return map[id] || `其他 (${id})`
}

const addLog = (message, type = 'info') => {
  logs.value.unshift({
    id: Date.now(),
    time: new Date().toLocaleString(),
    message,
    type
  })
  if (logs.value.length > 20) logs.value.pop()
}

onMounted(() => {
  loadOverview()
  loadLatestBehaviors()
})

onUnmounted(() => {
  if (progressTimer) clearInterval(progressTimer)
})
</script>

<style scoped lang="scss">
.data-manage {
  .el-col { margin-bottom: 24px; }

  .crawler-ui {
    .desc {
      font-size: 14px;
      color: var(--text-secondary);
      margin-bottom: 16px;
      line-height: 1.6;
    }
    .crawler-actions {
      display: flex;
      justify-content: center;
      margin-bottom: 16px;
    }
    .crawl-info {
      background: var(--bg-color-page);
      border-radius: 8px;
      padding: 12px;
    }
  }
  
  .import-progress {
    margin-top: 24px;
    padding: 16px;
    background: var(--bg-color-page);
    border-radius: 8px;
  }
  
  .analyze-tips {
    margin-top: 20px;
    background: rgba(16, 185, 129, 0.05);
    border: 1px solid rgba(16, 185, 129, 0.2);
    padding: 16px;
    border-radius: 8px;
    
    p { 
      color: #059669;
      margin-bottom: 8px;
    }
    ol { 
      margin: 0; 
      padding-left: 0;
      list-style: none;
      
      li {
        color: #059669;
        font-size: 13px;
        margin-bottom: 4px;
      }
    }
  }
  
  .overview-item {
    text-align: center;
    padding: 20px;
    background: var(--bg-color-page);
    border-radius: 8px;
    border: 1px solid var(--border-light);
    
    .label {
      color: var(--text-secondary);
      font-size: 14px;
      margin-bottom: 8px;
    }
    
    .value {
      font-size: 20px;
      font-weight: 700;
      color: var(--text-primary);
    }
  }
  
  .mt-2 { margin-top: 8px; }
}
</style>
