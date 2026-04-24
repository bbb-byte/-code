<template>
  <div class="product-analysis">
    <div class="page-header">
      <h2 class="page-title">商品分析</h2>
      <p class="page-desc">主要商品与类目表现分析，并补充展示京东公开评价摘要作为商品口碑解释指标</p>
    </div>

    <el-row :gutter="24">
      <el-col :xs="24" :md="12">
        <div class="card">
          <div class="card-title">热销商品 TOP10（按购买量）</div>
          <div class="chart-container" ref="buyChartRef"></div>
        </div>
      </el-col>

      <el-col :xs="24" :md="12">
        <div class="card">
          <div class="card-title">热门商品 TOP10（按浏览量）</div>
          <div class="chart-container" ref="viewChartRef"></div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="24">
      <el-col :xs="24" :md="12">
        <div class="card">
          <div class="card-title">热门类目 TOP10</div>
          <div class="chart-container" ref="categoryChartRef"></div>
        </div>
      </el-col>

      <el-col :xs="24" :md="12">
        <div class="card">
          <div class="card-title">商品行为对比</div>
          <div class="chart-container" ref="compareChartRef"></div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="24">
      <el-col :span="24">
        <div class="card metric-card">
          <div class="card-title">商品公网满意度补充指标（京东公开评价摘要）</div>
          <div class="metric-toolbar">
            <el-radio-group
              v-model="metricScope"
              size="small"
              :disabled="metricLoading"
              @change="handleMetricScopeChange"
            >
              <el-radio-button label="hot">热销商品</el-radio-button>
              <el-radio-button label="all">全部商品</el-radio-button>
            </el-radio-group>
            <el-switch
              v-model="metricOnlyWithMetrics"
              inline-prompt
              active-text="只看已补充"
              inactive-text="查看全部"
              :disabled="metricLoading"
              @change="handleMetricFilterChange"
            />
          </div>
          <div class="metric-summary">共 {{ metricTotal }} 条，当前第 {{ metricPage }} / {{ metricPageCount }} 页</div>
          <el-table :data="metricRows" stripe empty-text="当前暂无公网满意度数据">
            <el-table-column prop="productLabel" label="商品" min-width="180" />
            <el-table-column prop="buy_count" label="购买次数" width="110" />
            <el-table-column label="好评率" width="120">
              <template #default="{ row }">
                <span v-if="row.positive_rate !== null">{{ formatPositiveRate(row.positive_rate) }}</span>
                <el-tag v-else :type="metricFieldTagType(row, 'positive_rate')" size="small">{{ metricFieldPlaceholder(row, 'positive_rate') }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="review_count" label="评论总数" width="120">
              <template #default="{ row }">
                <span v-if="row.review_count !== null">{{ row.review_count }}</span>
                <el-tag v-else :type="metricFieldTagType(row, 'review_count')" size="small">{{ metricFieldPlaceholder(row, 'review_count') }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="shop_score" label="店铺评分" width="120">
              <template #default="{ row }">
                <span v-if="row.shop_score !== null">{{ Number(row.shop_score).toFixed(2) }}</span>
                <el-tag v-else :type="metricFieldTagType(row, 'shop_score')" size="small">{{ metricFieldPlaceholder(row, 'shop_score') }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="120">
              <template #default="{ row }">
                <el-tag :type="metricStatusMeta(row).type" size="small">{{ metricStatusMeta(row).label }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="说明" min-width="240">
              <template #default="{ row }">
                <span>{{ metricDescription(row) }}</span>
              </template>
            </el-table-column>
          </el-table>
          <div class="metric-pagination">
            <el-pagination
              background
              layout="total, prev, pager, next, sizes"
              :total="metricTotal"
              :current-page="metricPage"
              :page-size="metricPageSize"
              :page-sizes="[10, 20, 30, 50]"
              @current-change="handleMetricPageChange"
              @size-change="handleMetricPageSizeChange"
            />
          </div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script>
export default {
  name: 'ProductAnalysis'
}
</script>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { getHotProductsByBuy, getHotProductsWithPublicMetrics, getHotProductsByView, getHotCategories } from '@/api/analysis'
import * as echarts from 'echarts'

const buyChartRef = ref(null)
const viewChartRef = ref(null)
const categoryChartRef = ref(null)
const compareChartRef = ref(null)

let buyChart = null
let viewChart = null
let categoryChart = null
let compareChart = null

const metricRows = ref([])
const metricPage = ref(1)
const metricPageSize = ref(10)
const metricTotal = ref(0)
const metricPageCount = ref(1)
const metricOnlyWithMetrics = ref(true)
const metricScope = ref('hot')
const metricLoading = ref(false)

const normalizeMetricRow = (item) => ({
  ...item,
  productLabel: productLabel(item),
  positive_rate: item.positive_rate ?? null,
  review_count: item.review_count ?? null,
  shop_score: item.shop_score ?? null,
  crawl_status: item.crawl_status || 'pending'
})

const loadData = async () => {
  try {
    const [buyRes, metricRes, viewRes, categoryRes] = await Promise.all([
      getHotProductsByBuy(10),
      getHotProductsWithPublicMetrics(metricPage.value, metricPageSize.value, metricOnlyWithMetrics.value, metricScope.value),
      getHotProductsByView(10),
      getHotCategories(10)
    ])

    initCharts(buyRes.data, viewRes.data, categoryRes.data)

    const metricPayload = metricRes.data || {}
    metricTotal.value = Number(metricPayload.total || 0)
    metricPageCount.value = Math.max(1, Math.ceil(metricTotal.value / metricPageSize.value))
    metricRows.value = (metricPayload.rows || []).map(normalizeMetricRow)
  } catch (error) {
    console.error('加载数据失败:', error)
  }
}

const handleMetricPageChange = (page) => {
  metricPage.value = page
  loadMetricData()
}

const handleMetricPageSizeChange = (pageSize) => {
  metricPageSize.value = pageSize
  metricPage.value = 1
  loadMetricData()
}

const handleMetricFilterChange = async () => {
  if (metricLoading.value) return
  metricPage.value = 1
  await loadMetricData()
}

const handleMetricScopeChange = async () => {
  if (metricLoading.value) return
  metricPage.value = 1
  await loadMetricData()
}

const loadMetricData = async () => {
  if (metricLoading.value) return
  metricLoading.value = true
  try {
    const metricRes = await getHotProductsWithPublicMetrics(metricPage.value, metricPageSize.value, metricOnlyWithMetrics.value, metricScope.value)
    const metricPayload = metricRes.data || {}
    metricTotal.value = Number(metricPayload.total || 0)
    metricPageCount.value = Math.max(1, Math.ceil(metricTotal.value / metricPageSize.value))
    metricRows.value = (metricPayload.rows || []).map(normalizeMetricRow)
  } catch (error) {
    console.error('加载满意度指标失败:', error)
  } finally {
    metricLoading.value = false
  }
}

const productLabel = (item) => {
  if (!item) return ''
  if (item.verified_title) return item.verified_title
  if (item.product_name) return item.product_name
  if (item.brand) return `${item.brand} #${item.item_id}`
  return `ID ${item.item_id}`
}

const hasMetricValue = (value) => value !== null && value !== undefined

const hasAnyMetricValue = (row) => {
  if (!row) return false
  return hasMetricValue(row.positive_rate) || hasMetricValue(row.review_count) || hasMetricValue(row.shop_score)
}

const hasCoreMetricValue = (row) => {
  if (!row) return false
  return hasMetricValue(row.positive_rate) || hasMetricValue(row.shop_score)
}

const hasMapping = (row) => Number(row?.has_mapping || 0) === 1

const metricStatusMeta = (row) => {
  if (!hasMapping(row)) return { label: '未映射', type: 'info' }
  if (row.crawl_status === 'success' && hasCoreMetricValue(row)) return { label: '已补充', type: 'success' }
  if (row.crawl_status === 'success' && hasAnyMetricValue(row)) return { label: '部分补充', type: 'warning' }
  if (row.crawl_status === 'empty') return { label: '暂无公开值', type: 'info' }
  return { label: '待补充', type: 'info' }
}

const metricFieldPlaceholder = (row, field) => {
  if (!hasMapping(row)) return '未映射'
  if (row.crawl_status === 'empty') return '暂无公开值'
  if (row.crawl_status === 'success' && field !== 'review_count' && hasMetricValue(row.review_count)) return '部分补充'
  if (row.crawl_status === 'success') return '未提供'
  return '待补充'
}

const metricFieldTagType = (row, field) => (
  metricFieldPlaceholder(row, field) === '部分补充' ? 'warning' : 'info'
)

const metricDescription = (row) => {
  if (!hasMapping(row)) {
    return '当前商品尚未建立公网映射，暂不展示补充指标。'
  }
  if (row.crawl_status === 'empty') {
    return '已查询京东公开评价摘要，但当前未返回可用的满意度字段。'
  }
  if (row.crawl_status === 'success' && hasCoreMetricValue(row)) {
    return '该指标来自京东公开商品评价摘要，用于补充解释商品口碑，不代表平台内部真实点击日志。'
  }
  if (row.crawl_status === 'success' && hasAnyMetricValue(row)) {
    return '已采集到部分京东公开评价摘要字段，当前仅展示可用的补充指标。'
  }
  return '该指标来自京东公开商品评价摘要，用于补充解释商品口碑，不代表平台内部真实点击日志。'
}

const categoryLabel = (item) => {
  if (!item) return ''
  return item.category_name || `ID ${item.category_id}`
}

const formatPositiveRate = (value) => {
  const numeric = Number(value)
  if (!Number.isFinite(numeric)) return '-'
  const percent = numeric <= 1 ? numeric * 100 : numeric
  return `${percent.toFixed(2)}%`
}

const initCharts = (buyData, viewData, categoryData) => {
  const theme = {
    textMain: '#334155',
    textSub: '#94a3b8',
    axisLine: '#e2e8f0',
    splitLine: '#f1f5f9'
  }

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
        data: buyData.map(productLabel),
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
        data: viewData.map(productLabel),
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
          name: categoryLabel(item),
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

  if (compareChartRef.value && buyData && viewData) {
    compareChart = echarts.init(compareChartRef.value)
    const topProducts = buyData.slice(0, 5)
    const viewMap = Object.fromEntries(viewData.map(item => [String(item.item_id), item.view_count]))
    const products = topProducts.map(productLabel)

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
          formatter: (val) => val.length > 8 ? `${val.slice(0, 8)}...` : val
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
          data: topProducts.map(item => viewMap[String(item.item_id)] || 0),
          itemStyle: {
            color: '#4f46e5',
            borderRadius: [4, 4, 0, 0]
          }
        },
        {
          name: '购买',
          type: 'bar',
          data: topProducts.map(item => item.buy_count),
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

const debounce = (fn, delay) => {
  let timer = null
  return function () {
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
  .el-col {
    margin-bottom: 24px;
  }

  .metric-summary {
    margin-top: 8px;
    color: #64748b;
    font-size: 13px;
  }

  .metric-toolbar {
    margin-top: 8px;
    display: flex;
    justify-content: flex-end;
    gap: 12px;
    align-items: center;
  }

  .metric-card :deep(.el-table) {
    margin-top: 8px;
  }

  .metric-pagination {
    margin-top: 16px;
    display: flex;
    justify-content: flex-end;
  }
}
</style>
