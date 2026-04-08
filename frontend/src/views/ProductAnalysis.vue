<template>
  <div class="product-analysis">
    <div class="page-header">
      <h2 class="page-title">商品分析</h2>
      <p class="page-desc">主要商品及类目表现分析</p>
    </div>

    <el-row :gutter="24">
      <!-- 热销商品排行 -->
      <el-col :xs="24" :md="12">
        <div class="card">
          <div class="card-title">热销商品 TOP10（按购买量）</div>
          <div class="chart-container" ref="buyChartRef"></div>
        </div>
      </el-col>

      <!-- 热门浏览商品 -->
      <el-col :xs="24" :md="12">
        <div class="card">
          <div class="card-title">热门商品 TOP10（按浏览量）</div>
          <div class="chart-container" ref="viewChartRef"></div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="24">
      <!-- 热门类目 -->
      <el-col :xs="24" :md="12">
        <div class="card">
          <div class="card-title">热门类目 TOP10</div>
          <div class="chart-container" ref="categoryChartRef"></div>
        </div>
      </el-col>

      <!-- 商品行为转化 -->
      <el-col :xs="24" :md="12">
        <div class="card">
          <div class="card-title">商品行为对比</div>
          <div class="chart-container" ref="compareChartRef"></div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { getHotProductsByBuy, getHotProductsByView, getHotCategories } from '@/api/analysis'
import * as echarts from 'echarts'

const buyChartRef = ref(null)
const viewChartRef = ref(null)
const categoryChartRef = ref(null)
const compareChartRef = ref(null)

let buyChart = null
let viewChart = null
let categoryChart = null
let compareChart = null

const loadData = async () => {
  try {
    const [buyRes, viewRes, categoryRes] = await Promise.all([
      getHotProductsByBuy(10),
      getHotProductsByView(10),
      getHotCategories(10)
    ])
    initCharts(buyRes.data, viewRes.data, categoryRes.data)
  } catch (error) {
    console.error('加载数据失败:', error)
  }
}

const initCharts = (buyData, viewData, categoryData) => {
  // 浅色主题配色
  const theme = {
    textMain: '#334155',
    textSub: '#94a3b8',
    axisLine: '#e2e8f0',
    splitLine: '#f1f5f9'
  }

  // 热销商品柱状图
  if (buyChartRef.value && buyData) {
    buyChart = echarts.init(buyChartRef.value)
    buyChart.setOption({
      tooltip: { 
        trigger: 'axis',
        backgroundColor: '#fff',
        borderColor: '#e2e8f0',
        textStyle: { color: theme.textMain }
      },
      xAxis: {
        type: 'category',
        data: buyData.map(item => '商品' + item.item_id),
        axisLabel: { rotate: 45, interval: 0, color: theme.textSub },
        axisLine: { lineStyle: { color: theme.axisLine } },
        axisTick: { show: false }
      },
      yAxis: { 
        type: 'value', 
        name: '购买次数',
        nameTextStyle: { color: theme.textSub },
        axisLabel: { color: theme.textSub },
        axisLine: { lineStyle: { color: theme.axisLine } },
        splitLine: { lineStyle: { color: theme.splitLine } }
      },
      grid: { left: 50, right: 20, bottom: 60, top: 40 },
      series: [{
        type: 'bar',
        data: buyData.map(item => item.buy_count),
        barWidth: '50%',
        itemStyle: {
          borderRadius: [4, 4, 0, 0],
          color: '#ef4444'
        }
      }]
    })
  }

  // 热门浏览商品
  if (viewChartRef.value && viewData) {
    viewChart = echarts.init(viewChartRef.value)
    viewChart.setOption({
      tooltip: { 
        trigger: 'axis',
        backgroundColor: '#fff',
        borderColor: '#e2e8f0',
        textStyle: { color: theme.textMain }
      },
      xAxis: {
        type: 'category',
        data: viewData.map(item => '商品' + item.item_id),
        axisLabel: { rotate: 45, interval: 0, color: theme.textSub },
        axisLine: { lineStyle: { color: theme.axisLine } },
        axisTick: { show: false }
      },
      yAxis: { 
        type: 'value', 
        name: '浏览次数',
        nameTextStyle: { color: theme.textSub },
        axisLabel: { color: theme.textSub },
        axisLine: { lineStyle: { color: theme.axisLine } },
        splitLine: { lineStyle: { color: theme.splitLine } }
      },
      grid: { left: 50, right: 20, bottom: 60, top: 40 },
      series: [{
        type: 'bar',
        data: viewData.map(item => item.view_count),
        barWidth: '50%',
        itemStyle: {
          borderRadius: [4, 4, 0, 0],
          color: '#4f46e5'
        }
      }]
    })
  }

  // 热门类目
  if (categoryChartRef.value && categoryData) {
    categoryChart = echarts.init(categoryChartRef.value)
    categoryChart.setOption({
      tooltip: { 
        trigger: 'item',
        backgroundColor: '#fff',
        borderColor: '#e2e8f0',
        textStyle: { color: theme.textMain }
      },
      legend: { 
        bottom: 0, 
        type: 'scroll',
        textStyle: { color: theme.textSub }
      },
      color: ['#8b5cf6', '#06b6d4', '#22c55e', '#f59e0b', '#ef4444', '#ec4899', '#6366f1', '#14b8a6', '#f97316', '#84cc16'],
      series: [{
        type: 'pie',
        radius: ['40%', '70%'],
        center: ['50%', '45%'],
        data: categoryData.map(item => ({
          name: '类目' + item.category_id,
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

  // 商品行为对比
  if (compareChartRef.value && buyData && viewData) {
    compareChart = echarts.init(compareChartRef.value)
    const products = buyData.slice(0, 5).map(item => '商品' + item.item_id)
    
    compareChart.setOption({
      tooltip: { 
        trigger: 'axis',
        backgroundColor: '#fff',
        borderColor: '#e2e8f0',
        textStyle: { color: theme.textMain }
      },
      legend: { 
        data: ['浏览', '购买'],
        textStyle: { color: theme.textSub },
        top: 0
      },
      xAxis: { 
        type: 'category', 
        data: products,
        axisLabel: { 
          rotate: 30, 
          interval: 0, 
          color: theme.textSub,
          formatter: (val) => val.length > 8 ? val.slice(0, 8) + '…' : val
        },
        axisLine: { lineStyle: { color: theme.axisLine } },
        axisTick: { show: false }
      },
      yAxis: { 
        type: 'value',
        axisLabel: { color: theme.textSub },
        axisLine: { lineStyle: { color: theme.axisLine } },
        splitLine: { lineStyle: { color: theme.splitLine } }
      },
      grid: { left: 40, right: 20, bottom: 60, top: 40 },
      series: [
        { 
          name: '浏览', 
          type: 'bar', 
          data: viewData.slice(0, 5).map(item => item.view_count),
          itemStyle: { 
            color: '#4f46e5',
            borderRadius: [4, 4, 0, 0]
          }
        },
        { 
          name: '购买', 
          type: 'bar', 
          data: buyData.slice(0, 5).map(item => item.buy_count),
          itemStyle: { 
            color: '#10b981',
            borderRadius: [4, 4, 0, 0]
          }
        }
      ]
    })
  }
}

const handleResize = () => {
  buyChart?.resize()
  viewChart?.resize()
  categoryChart?.resize()
  compareChart?.resize()
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
  buyChart?.dispose()
  viewChart?.dispose()
  categoryChart?.dispose()
  compareChart?.dispose()
})
</script>

<style scoped lang="scss">
.product-analysis {
  .el-col { margin-bottom: 24px; }
}
</style>
