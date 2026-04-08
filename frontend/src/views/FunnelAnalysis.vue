<template>
  <div class="funnel-analysis">
    <div class="page-header">
      <h2 class="page-title">转化漏斗分析</h2>
      <p class="page-desc">用户购买行为转化路径与流失分析</p>
    </div>

    <el-row :gutter="24">
      <!-- 转化漏斗图 -->
      <el-col :xs="24" :md="12">
        <div class="card">
          <div class="card-title">用户行为转化漏斗</div>
          <div class="chart-container-lg" ref="funnelChartRef"></div>
        </div>
      </el-col>

      <!-- 转化率统计 -->
      <el-col :xs="24" :md="12">
        <div class="card">
          <div class="card-title">转化率详情</div>
          <div class="conversion-stats">
            <div class="conversion-item">
              <div class="step">
                <div class="step-icon view"><el-icon><View /></el-icon></div>
                <div class="step-info">
                  <div class="step-name">浏览用户</div>
                  <div class="step-value">{{ formatNumber(funnelData.pvUsers) }}</div>
                </div>
              </div>
              <div class="arrow">
                <el-icon><ArrowDown /></el-icon>
                <span class="rate">{{ pvToCartRate }}%</span>
              </div>
            </div>
            
            <div class="conversion-item">
              <div class="step">
                <div class="step-icon cart"><el-icon><ShoppingCart /></el-icon></div>
                <div class="step-info">
                  <div class="step-name">加购用户</div>
                  <div class="step-value">{{ formatNumber(funnelData.cartUsers) }}</div>
                </div>
              </div>
              <div class="arrow">
                <el-icon><ArrowDown /></el-icon>
                <span class="rate">{{ cartToBuyRate }}%</span>
              </div>
            </div>
            
            <div class="conversion-item">
              <div class="step">
                <div class="step-icon buy"><el-icon><CreditCard /></el-icon></div>
                <div class="step-info">
                  <div class="step-name">购买用户</div>
                  <div class="step-value">{{ formatNumber(funnelData.buyUsers) }}</div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="24">
      <!-- 整体转化率 -->
      <el-col :span="24">
        <div class="card">
          <div class="card-title">转化率仪表盘</div>
          <el-row :gutter="20">
            <el-col :xs="24" :md="8">
              <div class="chart-container" ref="gauge1Ref"></div>
            </el-col>
            <el-col :xs="24" :md="8">
              <div class="chart-container" ref="gauge2Ref"></div>
            </el-col>
            <el-col :xs="24" :md="8">
              <div class="chart-container" ref="gauge3Ref"></div>
            </el-col>
          </el-row>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { getConversionFunnel } from '@/api/analysis'
import * as echarts from 'echarts'
import { View, ShoppingCart, CreditCard, ArrowDown } from '@element-plus/icons-vue'

const funnelData = ref({
  pvUsers: 0,
  cartUsers: 0,
  buyUsers: 0
})

const funnelChartRef = ref(null)
const gauge1Ref = ref(null)
const gauge2Ref = ref(null)
const gauge3Ref = ref(null)

let funnelChart = null
let gauge1 = null
let gauge2 = null
let gauge3 = null

const formatNumber = (num) => {
  if (!num) return '0'
  if (num >= 10000) return (num / 10000).toFixed(1) + '万'
  return num.toLocaleString()
}

const pvToCartRate = computed(() => {
  if (!funnelData.value.pvUsers) return 0
  return ((funnelData.value.cartUsers / funnelData.value.pvUsers) * 100).toFixed(2)
})

const cartToBuyRate = computed(() => {
  if (!funnelData.value.cartUsers) return 0
  return ((funnelData.value.buyUsers / funnelData.value.cartUsers) * 100).toFixed(2)
})

const overallRate = computed(() => {
  if (!funnelData.value.pvUsers) return 0
  return ((funnelData.value.buyUsers / funnelData.value.pvUsers) * 100).toFixed(2)
})

const loadData = async () => {
  try {
    const res = await getConversionFunnel()
    funnelData.value = {
      pvUsers: res.data.pv_users,
      cartUsers: res.data.cart_users,
      buyUsers: res.data.buy_users
    }
    initCharts()
  } catch (error) {
    console.error('加载数据失败:', error)
  }
}

const initCharts = () => {
  const theme = {
    textMain: '#334155',
    textSub: '#94a3b8'
  }

  // 漏斗图
  if (funnelChartRef.value) {
    funnelChart = echarts.init(funnelChartRef.value)
    funnelChart.setOption({
      tooltip: { 
        trigger: 'item', 
        formatter: '{b} : {c}',
        backgroundColor: '#fff',
        borderColor: '#e2e8f0',
        textStyle: { color: theme.textMain }
      },
      series: [{
        type: 'funnel',
        left: '10%',
        width: '80%',
        top: 30,
        bottom: 30,
        minSize: '20%',
        maxSize: '100%',
        sort: 'descending',
        gap: 2,
        label: { 
          show: true, 
          position: 'inside', 
          formatter: '{b}\n{c}',
          color: '#fff',
          fontSize: 14,
          fontWeight: 'bold'
        },
        itemStyle: { 
          borderColor: '#fff', 
          borderWidth: 2 
        },
        data: [
          { value: funnelData.value.pvUsers, name: '浏览', itemStyle: { color: '#4f46e5' } },
          { value: funnelData.value.cartUsers, name: '加购', itemStyle: { color: '#0ea5e9' } },
          { value: funnelData.value.buyUsers, name: '购买', itemStyle: { color: '#10b981' } }
        ]
      }]
    })
  }

  // 仪表盘1
  if (gauge1Ref.value) {
    gauge1 = echarts.init(gauge1Ref.value)
    gauge1.setOption(getGaugeOption('浏览→加购转化率', parseFloat(pvToCartRate.value), '#4f46e5'))
  }

  // 仪表盘2
  if (gauge2Ref.value) {
    gauge2 = echarts.init(gauge2Ref.value)
    gauge2.setOption(getGaugeOption('加购→购买转化率', parseFloat(cartToBuyRate.value), '#0ea5e9'))
  }

  // 仪表盘3
  if (gauge3Ref.value) {
    gauge3 = echarts.init(gauge3Ref.value)
    gauge3.setOption(getGaugeOption('整体转化率', parseFloat(overallRate.value), '#10b981'))
  }
}

const getGaugeOption = (title, value, color) => ({
  series: [{
    type: 'gauge',
    startAngle: 180,
    endAngle: 0,
    min: 0,
    max: 100,
    splitNumber: 5,
    axisLine: {
      lineStyle: { 
        width: 20, 
        color: [
          [value / 100, color],
          [1, '#f1f5f9']
        ] 
      }
    },
    pointer: { 
      show: true, 
      length: '60%',
      itemStyle: { color: color }
    },
    axisTick: { show: false },
    splitLine: { show: false },
    axisLabel: { show: false },
    title: { 
      show: true, 
      offsetCenter: [0, '70%'], 
      fontSize: 14,
      color: '#64748b'
    },
    detail: {
      fontSize: 24,
      fontWeight: 'bold',
      offsetCenter: [0, '30%'],
      formatter: '{value}%',
      color: color
    },
    data: [{ value, name: title }]
  }]
})

const handleResize = () => {
  funnelChart?.resize()
  gauge1?.resize()
  gauge2?.resize()
  gauge3?.resize()
}

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
  funnelChart?.dispose()
  gauge1?.dispose()
  gauge2?.dispose()
  gauge3?.dispose()
})
</script>

<style scoped lang="scss">
.funnel-analysis {
  .el-col { margin-bottom: 24px; }
  
  .conversion-stats {
    padding: 16px;
    
    .conversion-item {
      .step {
        display: flex;
        align-items: center;
        gap: 16px;
        padding: 16px;
        background: var(--bg-color-overlay);
        border: 1px solid var(--border-color);
        border-radius: 12px;
        transition: all 0.3s ease;
        
        &:hover {
          transform: translateX(4px);
          box-shadow: var(--shadow-sm);
        }
        
        .step-icon {
          width: 48px;
          height: 48px;
          border-radius: 12px;
          display: flex;
          align-items: center;
          justify-content: center;
          color: #fff;
          font-size: 24px;
          
          &.view { background: linear-gradient(135deg, #6366f1, #4f46e5); }
          &.cart { background: linear-gradient(135deg, #38bdf8, #0ea5e9); }
          &.buy { background: linear-gradient(135deg, #34d399, #10b981); }
        }
        
        .step-info {
          .step-name { 
            color: var(--text-secondary); 
            font-size: 13px;
            margin-bottom: 4px;
          }
          .step-value { 
            font-size: 24px; 
            font-weight: 700; 
            color: var(--text-primary);
          }
        }
      }
      
      .arrow {
        display: flex;
        flex-direction: column;
        align-items: center;
        padding: 8px 0;
        color: var(--text-tertiary);
        
        .rate {
          font-size: 13px;
          color: var(--primary-color);
          font-weight: 600;
          margin-top: 2px;
        }
      }
    }
  }
}
</style>
