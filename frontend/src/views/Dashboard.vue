<template>
  <div class="dashboard">
    <div class="page-header">
      <h2 class="page-title">数据看板</h2>
      <p class="page-desc">实时监控平台关键指标与趋势</p>
    </div>

    <!-- 统计卡片 -->
    <el-row :gutter="24" class="stat-row">
      <el-col :xs="24" :sm="12" :md="6" v-for="(stat, index) in statCards" :key="stat.key">
        <div class="stat-card" :class="`delay-${index + 1}`">
          <div class="stat-content">
            <div class="stat-label">{{ stat.label }}</div>
            <div class="stat-value">{{ formatNumber(stats[stat.key]) }}</div>
            <div class="stat-trend neutral">
              <span>{{ stat.caption }}</span>
            </div>
          </div>
          <el-icon class="stat-icon"><component :is="stat.icon" /></el-icon>
        </div>
      </el-col>
    </el-row>

    <!-- 图表区第一行 -->
    <el-row :gutter="24" class="chart-row">
      <el-col :xs="24" :lg="12">
        <div class="card">
          <div class="card-title">
            <el-icon><PieChart /></el-icon>
            <span>行为类型分布</span>
          </div>
          <div class="chart-container" ref="behaviorChartRef"></div>
        </div>
      </el-col>
      
      <el-col :xs="24" :lg="12">
        <div class="card">
          <div class="card-title">
            <el-icon><DataLine /></el-icon>
            <span>每小时行为趋势</span>
          </div>
          <div class="chart-container" ref="hourlyChartRef"></div>
        </div>
      </el-col>
    </el-row>

    <!-- 图表区第二行 -->
    <el-row :gutter="24" class="chart-row">
      <el-col :xs="24" :lg="12">
        <div class="card">
          <div class="card-title">
            <el-icon><Trophy /></el-icon>
            <span>热销商品 TOP10</span>
          </div>
          <div class="chart-container" ref="hotProductsChartRef"></div>
        </div>
      </el-col>
      
      <el-col :xs="24" :lg="12">
        <div class="card">
          <div class="card-title">
            <el-icon><UserFilled /></el-icon>
            <span>用户分群分布</span>
          </div>
          <div class="chart-container" ref="userGroupChartRef"></div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, markRaw } from 'vue'
import { getDashboard } from '@/api/analysis'
import * as echarts from 'echarts'
import { User, Goods, Document, TrendCharts, PieChart, DataLine, Trophy, UserFilled } from '@element-plus/icons-vue'

const stats = ref({
  totalUsers: 0,
  totalProducts: 0,
  totalBehaviors: 0,
  conversionRate: 0
})

const statCards = [
  { key: 'totalUsers', label: '总用户数', icon: markRaw(User), caption: '当前已导入用户' },
  { key: 'totalProducts', label: '商品总数', icon: markRaw(Goods), caption: '当前已导入商品' },
  { key: 'totalBehaviors', label: '总交互次数', icon: markRaw(Document), caption: '全量行为记录' },
  { key: 'conversionRate', label: '购买转化率', icon: markRaw(TrendCharts), caption: '浏览到购买用户转化' }
]

// 图表引用
const behaviorChartRef = ref(null)
const hourlyChartRef = ref(null)
const hotProductsChartRef = ref(null)
const userGroupChartRef = ref(null)

let behaviorChart = null
let hourlyChart = null
let hotProductsChart = null
let userGroupChart = null

// 专业主题配色
const theme = {
  textMain: '#334155',
  textSub: '#94a3b8',
  axisLine: '#e2e8f0',
  splitLine: '#f1f5f9',
  colors: ['#4f46e5', '#0ea5e9', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6']
}

const formatNumber = (num) => {
  if (!num) return '0'
  if (typeof num === 'number' && num < 1) {
    return num.toFixed(2) + '%'
  }
  if (num >= 10000) {
    return (num / 10000).toFixed(1) + '万'
  }
  return num.toLocaleString()
}

const initCharts = (data) => {
  // 1. 行为类型分布 (环形图)
  if (behaviorChartRef.value && data.behaviorDistribution) {
    behaviorChart = echarts.init(behaviorChartRef.value)
    const behaviorTypeMap = { pv: '浏览', buy: '购买', cart: '加购', fav: '收藏' }
    const pieData = data.behaviorDistribution.map(item => ({
      name: behaviorTypeMap[item.behavior_type] || item.behavior_type,
      value: item.count
    }))
    
    behaviorChart.setOption({
      color: theme.colors,
      tooltip: { 
        trigger: 'item',
        backgroundColor: '#fff',
        borderColor: '#e2e8f0',
        textStyle: { color: theme.textMain },
        formatter: '{b}: {c} ({d}%)'
      },
      legend: { 
        bottom: 0,
        icon: 'circle',
        textStyle: { color: theme.textSub }
      },
      series: [{
        type: 'pie',
        radius: ['50%', '70%'],
        center: ['50%', '45%'],
        itemStyle: { 
          borderRadius: 4, 
          borderColor: '#fff', 
          borderWidth: 2 
        },
        label: { show: false },
        emphasis: { 
          label: { show: true, fontSize: 14, fontWeight: 'bold' } 
        },
        data: pieData
      }]
    })
  }
  
  // 2. 每小时行为Trends (平滑折线图)
  if (hourlyChartRef.value && data.hourlyDistribution) {
    hourlyChart = echarts.init(hourlyChartRef.value)
    const hours = data.hourlyDistribution.map(item => item.hour + ':00')
    const counts = data.hourlyDistribution.map(item => item.count)
    
    hourlyChart.setOption({
      color: ['#4f46e5'],
      tooltip: { 
        trigger: 'axis',
        backgroundColor: '#fff',
        borderColor: '#e2e8f0',
        textStyle: { color: theme.textMain }
      },
      grid: { left: 50, right: 20, top: 20, bottom: 30 },
      xAxis: { 
        type: 'category', 
        data: hours, 
        axisLine: { lineStyle: { color: theme.axisLine } },
        axisLabel: { color: theme.textSub },
        axisTick: { show: false }
      },
      yAxis: { 
        type: 'value',
        splitLine: { lineStyle: { color: theme.splitLine } },
        axisLabel: { color: theme.textSub }
      },
      series: [{
        type: 'line',
        data: counts,
        smooth: true,
        showSymbol: false,
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(79, 70, 229, 0.2)' },
            { offset: 1, color: 'rgba(79, 70, 229, 0)' }
          ])
        },
        lineStyle: { width: 3 }
      }]
    })
  }
  
  // 3. 热门商品 (圆角柱状图)
  if (hotProductsChartRef.value && data.hotProducts) {
    hotProductsChart = echarts.init(hotProductsChartRef.value)
    const products = data.hotProducts.map(item => item.product_name || (item.brand ? `${item.brand} #${item.item_id}` : `ID ${item.item_id}`))
    const buyCount = data.hotProducts.map(item => item.buy_count)
    
    hotProductsChart.setOption({
      color: ['#10b981'],
      tooltip: { 
        trigger: 'axis',
        backgroundColor: '#fff',
        borderColor: '#e2e8f0',
        textStyle: { color: theme.textMain }
      },
      grid: { left: 50, right: 20, top: 20, bottom: 60 },
      xAxis: { 
        type: 'category', 
        data: products, 
        axisLine: { lineStyle: { color: theme.axisLine } },
        axisLabel: { color: theme.textSub, rotate: 45 },
        axisTick: { show: false }
      },
      yAxis: { 
        type: 'value',
        splitLine: { lineStyle: { color: theme.splitLine } },
        axisLabel: { color: theme.textSub }
      },
      series: [{
        type: 'bar',
        data: buyCount,
        barWidth: '40%',
        itemStyle: { borderRadius: [4, 4, 0, 0] }
      }]
    })
  }
  
  // 4. 用户分群 (南丁格尔玫瑰图)
  if (userGroupChartRef.value && data.userGroupDistribution) {
    userGroupChart = echarts.init(userGroupChartRef.value)
    const groupData = data.userGroupDistribution.map(item => ({
      name: item.user_group || '未分类',
      value: item.count
    }))
    
    userGroupChart.setOption({
      color: theme.colors,
      tooltip: { 
        trigger: 'item',
        backgroundColor: '#fff',
        borderColor: '#e2e8f0',
        textStyle: { color: theme.textMain },
        formatter: '{b}: {c} ({d}%)'
      },
      legend: { 
        bottom: 0,
        icon: 'circle',
        textStyle: { color: theme.textSub }
      },
      series: [{
        type: 'pie',
        radius: ['20%', '70%'],
        center: ['50%', '45%'],
        roseType: 'area',
        itemStyle: { 
          borderRadius: 4,
          borderColor: '#fff',
          borderWidth: 2
        },
        label: { show: false },
        data: groupData
      }]
    })
  }
}

const handleResize = () => {
  behaviorChart?.resize()
  hourlyChart?.resize()
  hotProductsChart?.resize()
  userGroupChart?.resize()
}

onMounted(async () => {
  try {
    const res = await getDashboard()
    stats.value = {
      totalUsers: res.data.totalUsers,
      totalProducts: res.data.totalProducts,
      totalBehaviors: res.data.totalBehaviors,
      conversionRate: res.data.conversionRate
    }
    initCharts(res.data)
  } catch (error) {
    console.error('获取仪表盘数据失败:', error)
  }
  
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  behaviorChart?.dispose()
  hourlyChart?.dispose()
  hotProductsChart?.dispose()
  userGroupChart?.dispose()
})
</script>

<style scoped lang="scss">
.dashboard {
  .stat-row {
    margin-bottom: 24px;
  }
  
  .chart-row {
    margin-bottom: 24px;
  }
}

.delay-1 { animation: slideUp 0.4s ease-out 0.1s both; }
.delay-2 { animation: slideUp 0.4s ease-out 0.2s both; }
.delay-3 { animation: slideUp 0.4s ease-out 0.3s both; }
.delay-4 { animation: slideUp 0.4s ease-out 0.4s both; }

@keyframes slideUp {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
