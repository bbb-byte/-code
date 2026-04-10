<template>
  <div class="behavior-analysis">
    <div class="page-header">
      <h2 class="page-title">行为分析</h2>
      <p class="page-desc">用户交互行为趋势与分布统计</p>
    </div>

    <!-- 行为统计概览 -->
    <el-row :gutter="24" class="stat-row">
      <el-col :xs="24" :sm="12" :md="6" v-for="(stat, index) in visibleStatCards" :key="stat.key">
        <div class="stat-card">
          <div class="stat-content">
            <div class="stat-label">{{ stat.label }}</div>
            <div class="stat-value">{{ formatNumber(stats[stat.key]) }}</div>
          </div>
          <el-icon class="stat-icon" :class="stat.color"><component :is="stat.icon" /></el-icon>
        </div>
      </el-col>
    </el-row>

    <!-- 行为趋势图表 -->
    <el-row :gutter="24">
      <el-col :span="24">
        <div class="card">
          <div class="card-title">每日行为趋势</div>
          <div class="chart-container-lg" ref="trendChartRef"></div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="24">
      <el-col :xs="24" :md="12">
        <div class="card">
          <div class="card-title">行为类型占比</div>
          <div class="chart-container" ref="pieChartRef"></div>
        </div>
      </el-col>
      <el-col :xs="24" :md="12">
        <div class="card">
          <div class="card-title">每小时活跃度</div>
          <div class="chart-container" ref="hourlyChartRef"></div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, markRaw } from 'vue'
import { getDashboard, getDailyTrend } from '@/api/analysis'
import * as echarts from 'echarts'
import { View, ShoppingCart, Star, Goods } from '@element-plus/icons-vue'

const stats = ref({
  totalViews: 0,
  totalCarts: 0,
  totalBuys: 0
})

const statCards = [
  { key: 'totalViews', label: '总浏览次数', icon: markRaw(View), color: 'text-blue-500' },
  { key: 'totalCarts', label: '总加购次数', icon: markRaw(ShoppingCart), color: 'text-sky-500' },
  { key: 'totalFavs', label: '总收藏次数', icon: markRaw(Star), color: 'text-amber-500' },
  { key: 'totalBuys', label: '总购买次数', icon: markRaw(Goods), color: 'text-emerald-500' }
]

// The current archive dataset does not contain favorite events, so hide this summary card for now.
const visibleStatCards = statCards.filter(stat => stat.key !== 'totalFavs')

const trendChartRef = ref(null)
const pieChartRef = ref(null)
const hourlyChartRef = ref(null)

let trendChart = null
let pieChart = null
let hourlyChart = null

const formatNumber = (num) => {
  if (!num) return '0'
  if (num >= 10000) return (num / 10000).toFixed(1) + '万'
  return num.toLocaleString()
}

const loadData = async () => {
  try {
    const [dashboardRes, trendRes] = await Promise.all([
      getDashboard(),
      getDailyTrend()
    ])
    stats.value = {
      totalViews: dashboardRes.data.totalViews,
      totalCarts: dashboardRes.data.totalCarts,
      totalFavs: dashboardRes.data.totalFavs,
      totalBuys: dashboardRes.data.totalBuys
    }
    initCharts(dashboardRes.data, trendRes.data)
  } catch (error) {
    console.error('加载数据失败:', error)
  }
}

const initCharts = (data, trendRows) => {
  const theme = {
    textMain: '#334155',
    textSub: '#94a3b8',
    axisLine: '#e2e8f0',
    splitLine: '#f1f5f9'
  }

  if (trendChartRef.value) {
    trendChart = echarts.init(trendChartRef.value)
    const grouped = {}
    ;(trendRows || []).forEach(row => {
      const date = row.date
      if (!grouped[date]) {
        grouped[date] = { pv: 0, cart: 0, buy: 0 }
      }
      grouped[date][row.behavior_type] = row.count
    })
    const dates = Object.keys(grouped).sort()
    const pvData = dates.map(date => grouped[date].pv || 0)
    const buyData = dates.map(date => grouped[date].buy || 0)
    const cartData = dates.map(date => grouped[date].cart || 0)

    trendChart.setOption({
      tooltip: { 
        trigger: 'axis',
        backgroundColor: '#fff',
        borderColor: '#e2e8f0',
        textStyle: { color: theme.textMain }
      },
      legend: { 
        data: ['浏览', '加购', '购买'],
        textStyle: { color: theme.textSub },
        bottom: 0
      },
      grid: { left: 50, right: 20, bottom: 40, top: 40 },
      xAxis: { 
        type: 'category', 
        data: dates,
        axisLabel: { color: theme.textSub },
        axisLine: { lineStyle: { color: theme.axisLine } },
        axisTick: { show: false }
      },
      yAxis: { 
        type: 'value',
        axisLabel: { color: theme.textSub },
        axisLine: { lineStyle: { color: theme.axisLine } },
        splitLine: { lineStyle: { color: theme.splitLine } }
      },
      color: ['#4f46e5', '#0ea5e9', '#10b981'],
      series: [
        { 
          name: '浏览', 
          type: 'line', 
          data: pvData, 
          smooth: true,
          showSymbol: false,
          areaStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: 'rgba(79, 70, 229, 0.2)' },
              { offset: 1, color: 'rgba(79, 70, 229, 0)' }
            ])
          },
          lineStyle: { width: 3 }
        },
        { 
          name: '加购', 
          type: 'line', 
          data: cartData, 
          smooth: true,
          showSymbol: false,
          areaStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: 'rgba(14, 165, 233, 0.2)' },
              { offset: 1, color: 'rgba(14, 165, 233, 0)' }
            ])
          },
          lineStyle: { width: 3 }
        },
        { 
          name: '购买', 
          type: 'line', 
          data: buyData, 
          smooth: true,
          showSymbol: false,
          areaStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: 'rgba(16, 185, 129, 0.2)' },
              { offset: 1, color: 'rgba(16, 185, 129, 0)' }
            ])
          },
          lineStyle: { width: 3 }
        }
      ]
    })
  }

  // 行为类型饼图
  if (pieChartRef.value && data.behaviorDistribution) {
    pieChart = echarts.init(pieChartRef.value)
    const behaviorTypeMap = { pv: '浏览', buy: '购买', cart: '加购', fav: '收藏' }
    const pieData = data.behaviorDistribution.map(item => ({
      name: behaviorTypeMap[item.behavior_type] || item.behavior_type,
      value: item.count
    }))

    pieChart.setOption({
      tooltip: { 
        trigger: 'item', 
        formatter: '{b}: {c} ({d}%)',
        backgroundColor: '#fff',
        borderColor: '#e2e8f0',
        textStyle: { color: theme.textMain }
      },
      legend: { 
        bottom: 0,
        icon: 'circle',
        textStyle: { color: theme.textSub }
      },
      color: ['#4f46e5', '#10b981', '#0ea5e9', '#f59e0b'],
      series: [{
        type: 'pie',
        radius: ['50%', '70%'],
        center: ['50%', '45%'],
        data: pieData,
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

  // 每小时分布
  if (hourlyChartRef.value && data.hourlyDistribution) {
    hourlyChart = echarts.init(hourlyChartRef.value)
    const hours = data.hourlyDistribution.map(item => item.hour + ':00')
    const counts = data.hourlyDistribution.map(item => item.count)

    hourlyChart.setOption({
      tooltip: { 
        trigger: 'axis',
        backgroundColor: '#fff',
        borderColor: '#e2e8f0',
        textStyle: { color: theme.textMain }
      },
      xAxis: { 
        type: 'category', 
        data: hours, 
        axisLabel: { interval: 2, color: theme.textSub },
        axisLine: { lineStyle: { color: theme.axisLine } },
        axisTick: { show: false }
      },
      yAxis: { 
        type: 'value',
        axisLabel: { color: theme.textSub },
        axisLine: { lineStyle: { color: theme.axisLine } },
        splitLine: { lineStyle: { color: theme.splitLine } }
      },
      grid: { left: 50, right: 20, bottom: 40, top: 20 },
      series: [{
        type: 'bar',
        data: counts,
        barWidth: '60%',
        itemStyle: {
          borderRadius: [4, 4, 0, 0],
          color: '#0ea5e9'
        }
      }]
    })
  }
}

const handleResize = () => {
  if (trendChart) trendChart.resize()
  if (pieChart) pieChart.resize()
  if (hourlyChart) hourlyChart.resize()
}

// 防抖函数
const debounce = (fn, delay) => {
  let timer = null
  return function() {
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => {
      fn.apply(this, arguments)
    }, delay)
  }
}

const debouncedResize = debounce(handleResize, 200)

onMounted(() => {
  loadData()
  window.addEventListener('resize', debouncedResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', debouncedResize)
  trendChart?.dispose()
  pieChart?.dispose()
  hourlyChart?.dispose()
})
</script>

<style scoped lang="scss">
.behavior-analysis {
  .stat-row {
    margin-bottom: 24px;
  }
  
  .stat-card {
    .stat-label {
      color: var(--text-secondary);
      font-size: 14px;
      margin-bottom: 4px;
    }
    
    .stat-value {
      font-size: 24px;
      font-weight: 700;
      color: var(--text-primary);
    }
    
    .stat-icon {
      font-size: 24px;
      padding: 10px;
      border-radius: 12px;
      background: var(--bg-color-page);
      
      &.text-blue-500 { color: #4f46e5; background: #e0e7ff; }
      &.text-sky-500 { color: #0ea5e9; background: #bae6fd; }
      &.text-amber-500 { color: #f59e0b; background: #fef3c7; }
      &.text-emerald-500 { color: #10b981; background: #d1fae5; }
    }
  }
  
  .el-col { margin-bottom: 24px; }
}
</style>
