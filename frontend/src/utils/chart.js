import * as echarts from 'echarts'
import { onBeforeUnmount } from 'vue'

/**
 * ECharts 生命周期管理：setup 中调用 useChart()，
 * mountChart(el, option) 挂载图表；组件卸载时自动 dispose + 移除 resize 监听。
 */
export function useChart() {
  const instances = []
  const onResize = () => instances.forEach((c) => c.resize())

  window.addEventListener('resize', onResize)
  onBeforeUnmount(() => {
    window.removeEventListener('resize', onResize)
    instances.forEach((c) => c.dispose())
    instances.length = 0
  })

  function mountChart(el, option) {
    if (!el) return null
    const chart = echarts.init(el)
    chart.setOption(option)
    instances.push(chart)
    return chart
  }

  return { mountChart }
}

export const CHART_COLORS = ['#409EFF', '#67C23A', '#E6A23C', '#F56C6C', '#9333EA', '#13C2C2', '#FA8C16', '#722ED1']
