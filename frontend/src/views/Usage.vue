<template>
  <div>
    <el-card shadow="never" class="page-card">
      <div class="toolbar">
        <el-radio-group v-model="days" @change="loadSummary">
          <el-radio-button :value="7">近 7 天</el-radio-button>
          <el-radio-button :value="14">近 14 天</el-radio-button>
          <el-radio-button :value="30">近 30 天</el-radio-button>
        </el-radio-group>
        <div class="summary-mini">
          <span>总 Tokens：<b>{{ formatTokens(summary.totalTokens) }}</b></span>
          <span>总调用：<b>{{ formatNumber(summary.totalCalls) }}</b></span>
          <span>缓存命中率：<b>{{ cacheHitPercent }}%</b></span>
        </div>
      </div>
    </el-card>

    <el-row :gutter="16" class="page-card">
      <el-col :span="16">
        <el-card shadow="never">
          <template #header><span class="card-title">用量趋势</span></template>
          <div ref="trendChartEl" class="chart-box"></div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never">
          <template #header><span class="card-title">模型分布</span></template>
          <div ref="modelChartEl" class="chart-box"></div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="page-card">
      <template #header><span class="card-title">Top 应用用量（tokens）</span></template>
      <div ref="appChartEl" class="chart-box-sm"></div>
    </el-card>

    <el-card shadow="never">
      <template #header><span class="card-title">调用流水明细</span></template>
      <el-table v-loading="pageLoading" :data="records" stripe>
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="应用" min-width="130">
          <template #default="{ row }">{{ row.appName || `应用#${row.appId}` }}</template>
        </el-table-column>
        <el-table-column prop="model" label="模型" min-width="120" />
        <el-table-column label="Prompt" width="100" align="right">
          <template #default="{ row }">{{ formatNumber(row.promptTokens) }}</template>
        </el-table-column>
        <el-table-column label="Completion" width="110" align="right">
          <template #default="{ row }">{{ formatNumber(row.completionTokens) }}</template>
        </el-table-column>
        <el-table-column label="总 Tokens" width="110" align="right">
          <template #default="{ row }">
            <b>{{ formatNumber(row.totalTokens) }}</b>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="100" align="right">
          <template #default="{ row }">{{ row.latencyMs }} ms</template>
        </el-table-column>
        <el-table-column label="缓存命中" width="100" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.cacheHit" type="success" size="small" effect="light">命中</el-tag>
            <el-tag v-else type="info" size="small" effect="plain">未命中</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination
          v-model:current-page="pageNum"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="loadPage"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { getUsageSummary, getUsagePage } from '../api/usage'
import { formatTokens, formatNumber, formatDateTime, toPercent } from '../utils/format'
import { useChart, CHART_COLORS } from '../utils/chart'

const { mountChart } = useChart()

const days = ref(7)
const summary = reactive({ totalTokens: 0, totalCalls: 0, cacheHitRate: 0, daily: [], byModel: [], topApps: [] })
const cacheHitPercent = computed(() => toPercent(summary.cacheHitRate).toFixed(1))

const trendChartEl = ref()
const modelChartEl = ref()
const appChartEl = ref()

const pageLoading = ref(false)
const records = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)

function renderCharts() {
  const daily = Array.isArray(summary.daily) ? summary.daily : []
  mountChart(trendChartEl.value, {
    color: CHART_COLORS,
    tooltip: { trigger: 'axis' },
    legend: { data: ['Tokens', '调用次数'], top: 0 },
    grid: { left: 70, right: 55, top: 40, bottom: 30 },
    xAxis: { type: 'category', boundaryGap: false, data: daily.map((d) => d.day) },
    yAxis: [{ type: 'value', name: 'Tokens' }, { type: 'value', name: '次数', minInterval: 1 }],
    series: [
      { name: 'Tokens', type: 'line', smooth: true, areaStyle: { opacity: 0.15 }, symbolSize: 6, data: daily.map((d) => d.tokens ?? 0) },
      { name: '调用次数', type: 'line', smooth: true, yAxisIndex: 1, symbolSize: 6, data: daily.map((d) => d.calls ?? 0) }
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
        radius: ['40%', '64%'],
        center: ['50%', '44%'],
        itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
        label: { formatter: '{b}\n{d}%' },
        data: byModel.map((m) => ({ name: m.model, value: m.tokens ?? 0 }))
      }
    ]
  })

  const topApps = Array.isArray(summary.topApps) ? summary.topApps : []
  mountChart(appChartEl.value, {
    color: CHART_COLORS,
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 110, right: 40, top: 16, bottom: 30 },
    xAxis: { type: 'value', name: 'tokens' },
    yAxis: {
      type: 'category',
      inverse: true,
      data: topApps.map((a) => a.appName || `应用#${a.appId}`)
    },
    series: [
      {
        type: 'bar',
        barMaxWidth: 20,
        itemStyle: { borderRadius: [0, 4, 4, 0] },
        label: { show: true, position: 'right', formatter: (p) => formatTokens(p.value) },
        data: topApps.map((a) => a.tokens ?? 0)
      }
    ]
  })
}

async function loadSummary() {
  const s = await getUsageSummary(days.value).catch(() => null)
  if (s) {
    Object.assign(summary, s)
    renderCharts()
  }
}

async function loadPage() {
  pageLoading.value = true
  try {
    const res = await getUsagePage({ pageNum: pageNum.value, pageSize: pageSize.value })
    records.value = res?.records || []
    total.value = res?.total || 0
  } finally {
    pageLoading.value = false
  }
}

function handleSizeChange() {
  pageNum.value = 1
  loadPage()
}

onMounted(() => {
  loadSummary()
  loadPage()
})
</script>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.summary-mini {
  display: flex;
  gap: 28px;
  color: #606266;
}

.summary-mini b {
  color: #303133;
}

.chart-box {
  height: 320px;
}

.chart-box-sm {
  height: 240px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}
</style>
