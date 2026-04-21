<template>
  <div class="user-profile">
    <div class="page-header">
      <h2 class="page-title">用户画像</h2>
      <div class="header-actions">
        <el-button type="primary" @click="handleCalculateRFM" :loading="calculating">
          <el-icon><Refresh /></el-icon> 计算RFM
        </el-button>
        <el-button @click="handleClustering" :loading="clustering">
          <el-icon><SetUp /></el-icon> 聚类分析
        </el-button>
      </div>
    </div>

    <el-row :gutter="24">
      <!-- RFM模型说明 -->
      <el-col :xs="24" :md="8">
        <div class="card">
          <div class="card-title">RFM模型说明</div>
          <div class="rfm-intro">
            <div class="rfm-item">
              <div class="rfm-label">R (Recency)</div>
              <div class="rfm-desc">最近一次消费距今的时间</div>
            </div>
            <div class="rfm-item">
              <div class="rfm-label">F (Frequency)</div>
              <div class="rfm-desc">消费频率，购买次数</div>
            </div>
            <div class="rfm-item">
              <div class="rfm-label">M (Monetary)</div>
              <div class="rfm-desc">消费金额总和</div>
            </div>
          </div>
        </div>
      </el-col>

      <!-- 用户分群分布 -->
      <el-col :xs="24" :md="8">
        <div class="card">
          <div class="card-title">用户分群分布</div>
          <div class="chart-container" ref="groupChartRef"></div>
        </div>
      </el-col>

      <!-- RFM评分分布 -->
      <el-col :xs="24" :md="8">
        <div class="card">
          <div class="card-title">RFM评分分布</div>
          <div class="chart-container" ref="rfmChartRef"></div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="24">
      <!-- 聚类结果 -->
      <el-col :xs="24" :md="12">
        <div class="card">
          <div class="card-title">聚类分布</div>
          <div class="chart-container" ref="clusterChartRef"></div>
        </div>
      </el-col>

      <!-- 聚类中心雷达图 -->
      <el-col :xs="24" :md="12">
        <div class="card">
          <div class="card-title">聚类中心特征</div>
          <div class="chart-container" ref="radarChartRef"></div>
        </div>
      </el-col>
    </el-row>

    <!-- 用户排行列表 -->
    <div class="card">
      <div class="card-header" style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;">
        <div class="card-title" style="margin-bottom:0">用户排行 TOP20</div>
        <el-select v-model="selectedGroup" placeholder="选择分群" @change="loadTopUsers" style="width: 160px">
          <el-option label="全部用户" value="all" />
          <el-option label="高价值用户" value="高价值用户" />
          <el-option label="潜力用户" value="潜力用户" />
          <el-option label="新用户" value="新用户" />
          <el-option label="沉睡用户" value="沉睡用户" />
          <el-option label="流失用户" value="流失用户" />
          <el-option label="未转化用户" value="未转化用户" />
        </el-select>
      </div>
      <el-table :data="highValueUsers" stripe>
        <el-table-column prop="userId" label="用户ID" width="120" />
        <el-table-column prop="rScore" label="R评分" width="80">
          <template #default="{ row }">
            <el-tag :type="row.rScore >= 4 ? 'success' : (row.rScore >= 3 ? 'warning' : 'info')">{{ row.rScore }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="fScore" label="F评分" width="80">
          <template #default="{ row }">
            <el-tag :type="row.fScore >= 4 ? 'success' : (row.fScore >= 3 ? 'warning' : 'info')">{{ row.fScore }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="mScore" label="M评分" width="80">
          <template #default="{ row }">
            <el-tag :type="row.mScore >= 4 ? 'success' : (row.mScore >= 3 ? 'warning' : 'info')">{{ row.mScore }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="rfmScore" label="RFM总分" width="100" />
        <el-table-column prop="userGroup" label="用户分群" width="120">
          <template #default="{ row }">
            <el-tag :type="getGroupTag(row.userGroup)" effect="plain">{{ row.userGroup }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="totalBuys" label="购买次数" width="100" />
        <el-table-column prop="totalViews" label="浏览次数" width="100" />
        <el-table-column prop="conversionRate" label="转化率" width="100">
          <template #default="{ row }">
            {{ (row.conversionRate * 100).toFixed(2) }}%
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script>
export default {
  name: 'UserProfile'
}
</script>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { getGroupDistribution, getClusterDistribution, getRFMDistribution, 
         getClusterCenters, getHighValueUsers, getTopUsers, calculateRFM, performClustering } from '@/api/profile'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { Refresh, SetUp } from '@element-plus/icons-vue'

const calculating = ref(false)
const clustering = ref(false)
const highValueUsers = ref([])
const selectedGroup = ref('all')

const groupChartRef = ref(null)
const rfmChartRef = ref(null)
const clusterChartRef = ref(null)
const radarChartRef = ref(null)

let groupChart = null
let rfmChart = null
let clusterChart = null
let radarChart = null

const handleCalculateRFM = async () => {
  calculating.value = true
  try {
    await calculateRFM()
    ElMessage.success('RFM计算完成')
    loadData()
  } catch (error) {
    console.error('RFM计算失败:', error)
  } finally {
    calculating.value = false
  }
}

const handleClustering = async () => {
  clustering.value = true
  try {
    await performClustering(5)
    ElMessage.success('聚类分析完成')
    loadData()
  } catch (error) {
    console.error('聚类失败:', error)
  } finally {
    clustering.value = false
  }
}

const loadTopUsers = async () => {
  try {
    const res = await getTopUsers(selectedGroup.value, 20)
    highValueUsers.value = res.data || []
  } catch (error) {
    console.error('加载用户排行失败:', error)
  }
}

const getGroupTag = (group) => {
  const map = {
    '高价值用户': 'danger',
    '潜力用户': 'warning',
    '新用户': 'success',
    '沉睡用户': 'info',
    '流失用户': '',
    '未转化用户': 'info'
  }
  return map[group] || 'info'
}

const loadData = async () => {
  try {
    const [groupRes, rfmRes, clusterRes, centersRes] = await Promise.all([
      getGroupDistribution(),
      getRFMDistribution(),
      getClusterDistribution(),
      getClusterCenters()
    ])
    
    initCharts(groupRes.data, rfmRes.data, clusterRes.data, centersRes.data)
    loadTopUsers()
  } catch (error) {
    console.error('加载数据失败:', error)
  }
}

const CLUSTER_BUSINESS_LABELS = ['高价值用户', '潜力用户', '一般用户', '沉睡用户', '流失用户']

/**
 * 根据聚类中心RFM特征动态分配业务标签：
 * 频次高、金额高、近期购买（recency低）的簇 → 高价值用户；依次递减。
 */
const buildClusterLabelMap = (centers) => {
  if (!centers?.length) return {}
  const valid = centers.filter(c => c.cluster_id != null && Number(c.cluster_id) >= 0)
  const maxR = Math.max(...valid.map(c => Number(c.avg_recency) || 0)) || 1
  const maxF = Math.max(...valid.map(c => Number(c.avg_frequency) || 0)) || 1
  const maxM = Math.max(...valid.map(c => Number(c.avg_monetary) || 0)) || 1
  // 综合得分：频率/金额越高越好，recency（距上次购买天数）越低越好
  const scored = valid.map(c => ({
    id: c.cluster_id,
    score: (Number(c.avg_frequency) || 0) / maxF
         + (Number(c.avg_monetary) || 0) / maxM
         - (Number(c.avg_recency) || 0) / maxR
  }))
  scored.sort((a, b) => b.score - a.score)
  const map = {}
  scored.forEach((c, i) => { map[c.id] = CLUSTER_BUSINESS_LABELS[i] || `聚类${i + 1}` })
  return map
}

const initCharts = (groupData, rfmData, clusterData, centersData) => {
  // 专业浅色主题
  const theme = {
    textMain: '#334155',
    textSub: '#94a3b8',
    axisLine: '#e2e8f0',
    splitLine: '#f1f5f9'
  }

  // 基于聚类中心特征构建业务标签映射（在渲染两个聚类图前先算好）
  const clusterLabelMap = buildClusterLabelMap(centersData)

  // 用户分群饼图
  if (groupChartRef.value && groupData) {
    groupChart = echarts.init(groupChartRef.value)
    groupChart.setOption({
      tooltip: { 
        trigger: 'item',
        backgroundColor: '#fff',
        borderColor: '#e2e8f0',
        textStyle: { color: theme.textMain }
      },
      legend: { 
        bottom: 0,
        icon: 'circle',
        textStyle: { color: theme.textSub }
      },
      color: ['#4f46e5', '#10b981', '#f59e0b', '#ef4444', '#0ea5e9'],
      series: [{
        type: 'pie',
        radius: '65%',
        center: ['50%', '45%'],
        data: groupData.map(item => ({ name: item.user_group || '未分类', value: item.count })),
        itemStyle: { 
          borderRadius: 4,
          borderColor: '#fff',
          borderWidth: 2
        },
        label: { show: false },
        emphasis: {
          label: { show: true, fontSize: 14, fontWeight: 'bold' }
        }
      }]
    })
  }

  // RFM评分分布
  if (rfmChartRef.value && rfmData) {
    rfmChart = echarts.init(rfmChartRef.value)
    rfmChart.setOption({
      tooltip: { 
        trigger: 'axis',
        backgroundColor: '#fff',
        borderColor: '#e2e8f0',
        textStyle: { color: theme.textMain }
      },
      xAxis: { 
        type: 'category', 
        data: rfmData.map(item => item.rfm_score),
        axisLabel: { color: theme.textSub },
        axisLine: { lineStyle: { color: theme.axisLine } },
        axisTick: { show: false }
      },
      yAxis: { 
        type: 'value',
        minInterval: 1,
        axisLabel: {
          color: theme.textSub,
          formatter: (value) => Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 0 })
        },
        axisLine: { lineStyle: { color: theme.axisLine } },
        splitLine: { lineStyle: { color: theme.splitLine } }
      },
      grid: { left: 64, right: 20, bottom: 30, top: 20, containLabel: true },
      series: [{
        type: 'bar',
        data: rfmData.map(item => item.count),
        barWidth: '60%',
        itemStyle: { 
          borderRadius: [4, 4, 0, 0],
          color: '#10b981'
        }
      }]
    })
  }

  // 聚类分布
  if (clusterChartRef.value && clusterData) {
    // 过滤掉 cluster_id 为 null/-1 的无效行，只保留 K-Means 有效簇
    const validClusterData = clusterData.filter(
      item => item.cluster_id != null && Number(item.cluster_id) >= 0
    )
    clusterChart = echarts.init(clusterChartRef.value)
    clusterChart.setOption({
      tooltip: {
        trigger: 'item',
        backgroundColor: '#fff',
        borderColor: '#e2e8f0',
        textStyle: { color: theme.textMain }
      },
      legend: {
        bottom: 0,
        icon: 'circle',
        textStyle: { color: theme.textSub }
      },
      color: ['#8b5cf6', '#0ea5e9', '#f59e0b', '#ef4444', '#10b981'],
      series: [{
        type: 'pie',
        radius: ['40%', '70%'],
        center: ['50%', '45%'],
        data: validClusterData.map(item => ({
          name: clusterLabelMap[item.cluster_id] || `聚类${Number(item.cluster_id) + 1}`,
          value: item.count
        })),
        itemStyle: {
          borderRadius: 4,
          borderColor: '#fff',
          borderWidth: 2
        },
        label: { show: false },
        emphasis: {
          label: { show: true, fontSize: 14, fontWeight: 'bold' }
        }
      }]
    })
  }

  // 雷达图
  if (radarChartRef.value && centersData?.length > 0) {
    // 过滤掉 cluster_id 为 null/-1 的无效行
    const validCentersData = centersData.filter(
      item => item.cluster_id != null && Number(item.cluster_id) >= 0
    )
    radarChart = echarts.init(radarChartRef.value)
    radarChart.setOption({
      tooltip: {
        backgroundColor: '#fff',
        borderColor: '#e2e8f0',
        textStyle: { color: theme.textMain }
      },
      legend: {
        bottom: 0,
        icon: 'circle',
        textStyle: { color: theme.textSub }
      },
      color: ['#4f46e5', '#10b981', '#f59e0b', '#ef4444', '#0ea5e9'],
      radar: {
        indicator: [
          { name: 'R值', max: 5 },
          { name: 'F值', max: 5 },
          { name: 'M值', max: 5 }
        ],
        axisName: { color: theme.textSub },
        splitLine: { lineStyle: { color: theme.splitLine } },
        splitArea: { areaStyle: { color: ['rgba(241, 245, 249, 0.4)', 'rgba(241, 245, 249, 1)'] } },
        axisLine: { lineStyle: { color: theme.axisLine } }
      },
      series: [{
        type: 'radar',
        data: validCentersData.map(item => ({
          name: clusterLabelMap[item.cluster_id] || `聚类${Number(item.cluster_id) + 1}`,
          value: [Number(item.avg_recency) || 0, Number(item.avg_frequency) || 0, Number(item.avg_monetary) || 0],
          areaStyle: { opacity: 0.2 }
        })),
        symbol: 'none'
      }]
    })
  }
}

const handleResize = () => {
  groupChart?.resize()
  rfmChart?.resize()
  clusterChart?.resize()
  radarChart?.resize()
}

onMounted(() => {
  loadData()
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  groupChart?.dispose()
  rfmChart?.dispose()
  clusterChart?.dispose()
  radarChart?.dispose()
})
</script>

<style scoped lang="scss">
.user-profile {
  .el-col { margin-bottom: 24px; }
  
  .page-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 24px;
    
    .page-title { margin-bottom: 0; }
    
    .header-actions {
      .el-button { margin-left: 12px; }
    }
  }
  
  .rfm-intro {
    .rfm-item {
      padding: 16px;
      border-bottom: 1px solid var(--border-light);
      
      &:last-child { border-bottom: none; }
      
      .rfm-label {
        font-size: 16px;
        font-weight: 600;
        color: var(--primary-color);
        margin-bottom: 4px;
      }
      
      .rfm-desc {
        color: var(--text-secondary);
        font-size: 14px;
      }
    }
  }
}
</style>
