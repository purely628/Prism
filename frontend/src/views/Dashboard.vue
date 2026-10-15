<template>
  <div v-loading="loading">
    <el-row :gutter="16">
      <el-col :span="6">
        <el-card class="stat-card" shadow="hover">
          <div class="stat-icon" style="background: linear-gradient(135deg, #409eff, #79bbff)">
            <el-icon><Coin /></el-icon>
          </div>
          <div>
            <div class="stat-value">{{ formatTokens(summary.totalTokens) }}</div>
            <div class="stat-label">近 7 天总 Tokens</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card" shadow="hover">
          <div class="stat-icon" style="background: linear-gradient(135deg, #67c23a, #95d475)">
            <el-icon><Histogram /></el-icon>
          </div>
          <div>
            <div class="stat-value">{{ formatNumber(summary.totalCalls) }}</div>
            <div class="stat-label">近 7 天总调用次数</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card" shadow="hover">
          <div class="stat-icon" style="background: linear-gradient(135deg, #e6a23c, #eebe77)">
            <el-icon><Lightning /></el-icon>
          </div>
          <div>
            <div class="stat-value">{{ cacheHitPercent }}%</div>
            <div class="stat-label">缓存命中率</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card quota-card" shadow="hover">
          <div class="stat-icon" style="background: linear-gradient(135deg, #9333ea, #c084fc)">
            <el-icon><DataLine /></el-icon>
          </div>
          <div class="quota-body">
            <div class="stat-value">{{ quotaPercent }}%</div>
            <div class="stat-label">
              配额使用（{{ quota.month || '本月' }}）
              <span class="quota-detail">{{ formatTokens(quota.used) }} / {{ formatTokens(quota.monthlyQuota) }}</span>
            </div>
            <el-progress
              :percentage="quotaPercent"
              :stroke-width="8"
              :color="quotaColor"
              style="margin-top: 6px"
            />
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-top: 16px">
      <el-col :span="16">
        <el-card shadow="never">
          <template #header><span class="card-title">每日 Tokens / 调用次数（近 7 天）</span></template>
          <div ref="dailyChartEl" class="chart-box"></div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never">
          <template #header><span class="card-title">模型用量分布</span></template>
          <div ref="modelChartEl" class="chart-box"></div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { Coin, Histogram, Lightning, DataLine } from '@element-plus/icons-vue'
import { getUsageSummary, getUsageQuota } from '../api/usage'
import { formatTokens, formatNumber, toPercent } from '../utils/format'
import { useChart, CHART_COLORS } from '../utils/chart'

const { mountChart } = useChart()

const loading = ref(false)
const summary = reactive({ totalTokens: 0, totalCalls: 0, cacheHitRate: 0, daily: [], byModel: [], topApps: [] })
const quota = reactive({ monthlyQuota: 0, used: 0, month: '' })

const dailyChartEl = ref()
const modelChartEl = ref()

const cacheHitPercent = computed(() => toPercent(summary.cacheHitRate).toFixed(1))
const quotaPercent = computed(() => {
  if (!quota.monthlyQuota) return 0
  return Math.min(100, Math.round((quota.used / quota.monthlyQuota) * 100))
})
const quotaColor = computed(() => {
  const p = quotaPercent.value
  if (p >= 90) return '#f56c6c'
  if (p >= 70) return '#e6a23c'
  return '#67c23a'
})

function renderCharts() {
  const daily = Array.isArray(summary.daily) ? summary.daily : []
  mountChart(dailyChartEl.value, {
    color: CHART_COLORS,
    tooltip: { trigger: 'axis' },
    legend: { data: ['Tokens', '调用次数'], top: 0 },
    grid: { left: 70, right: 55, top: 40, bottom: 30 },
    xAxis: { type: 'category', data: daily.map((d) => d.day) },
    yAxis: [
      { type: 'value', name: 'Tokens' },
      { type: 'value', name: '次数', minInterval: 1 }
    ],
    series: [
      {
        name: 'Tokens',
        type: 'bar',
        barMaxWidth: 32,
        itemStyle: { borderRadius: [4, 4, 0, 0] },
        data: daily.map((d) => d.tokens ?? 0)
      },
      {
        name: '调用次数',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        symbolSize: 7,
        data: daily.map((d) => d.calls ?? 0)
      }
    ]
  })

  const byModel = Array.isArray(summary.byModel) ? summary.byModel : []
  mountChart(modelChartEl.value, {
    color: CHART_COLORS,
    tooltip: { trigger: 'item', formatter: '{b}: {c} tokens ({d}%)' },
    legend: { bottom: 0, type: 'scroll' },
    series: [
      {
        type: 'pie',
        radius: ['42%', '66%'],
        center: ['50%', '44%'],
        avoidLabelOverlap: true,
        itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
        label: { formatter: '{b}\n{d}%' },
        data: byModel.map((m) => ({ name: m.model, value: m.tokens ?? 0 }))
      }
    ]
  })
}

async function loadData() {
  loading.value = true
  try {
    const [s, q] = await Promise.all([
      getUsageSummary(7).catch(() => null),
      getUsageQuota(1).catch(() => null)
    ])
    if (s) Object.assign(summary, s)
    if (q) Object.assign(quota, q)
    renderCharts()
  } finally {
    loading.value = false
  }
}

onMounted(loadData)
</script>

<style scoped>
.chart-box {
  height: 340px;
}

.quota-card :deep(.el-card__body) {
  align-items: flex-start;
}

.quota-body {
  flex: 1;
  min-width: 0;
}

.quota-detail {
  display: block;
  font-size: 12px;
}
</style>
