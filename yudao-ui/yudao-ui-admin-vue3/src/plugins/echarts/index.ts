import * as echarts from 'echarts/core'

import {
  BarChart,
  FunnelChart,
  GaugeChart,
  LineChart,
  MapChart,
  PictorialBarChart,
  PieChart,
  RadarChart
} from 'echarts/charts'

import {
  AriaComponent,
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  ParallelComponent,
  PolarComponent,
  TitleComponent,
  ToolboxComponent,
  TooltipComponent,
  VisualMapComponent
} from 'echarts/components'

import { CanvasRenderer } from 'echarts/renderers'

echarts.use([
  LegendComponent,
  TitleComponent,
  TooltipComponent,
  ToolboxComponent,
  DataZoomComponent,
  GridComponent,
  PolarComponent,
  AriaComponent,
  ParallelComponent,
  VisualMapComponent,
  BarChart,
  LineChart,
  PieChart,
  MapChart,
  CanvasRenderer,
  PictorialBarChart,
  RadarChart,
  GaugeChart,
  FunnelChart
])

// SEP-04：大屏暗色主题「山乡夜航 · 山泉蓝调」——深蓝墨底 + 山泉蓝提亮 + 稻谷金/陶土橙点缀，
// 刻意不用 DataV 式霓虹青紫；大屏图表 init 时传 `theme: 'cargo-dark'` 即可，无需逐图配色。
// 注意：绿色仅用于"成功/在线"语义（与主色蓝区分），不参与序列取色。
echarts.registerTheme('cargo-dark', {
  backgroundColor: 'transparent', // 背景交给面板（#111B2B），图表自身透明便于叠加
  color: ['#4F93D6', '#E8B855', '#E07A3F', '#7FB3E3', '#B98FD6', '#5FBFAF', '#D67BA8', '#93A5BC'],
  textStyle: {},
  title: {
    textStyle: { color: '#E6EDF5', fontWeight: 600, fontSize: 15 },
    subtextStyle: { color: '#93A5BC' }
  },
  legend: {
    textStyle: { color: '#93A5BC', fontSize: 12 },
    pageTextStyle: { color: '#93A5BC' },
    inactiveColor: '#3A4A61'
  },
  tooltip: {
    backgroundColor: 'rgba(17, 27, 43, 0.95)',
    borderColor: '#1D2C42',
    textStyle: { color: '#E6EDF5', fontSize: 12 }
  },
  categoryAxis: {
    axisLine: { lineStyle: { color: '#23364F' } },
    axisTick: { lineStyle: { color: '#23364F' } },
    axisLabel: { color: '#93A5BC', fontSize: 11 },
    splitLine: { show: false }
  },
  valueAxis: {
    axisLine: { show: false },
    axisTick: { show: false },
    axisLabel: { color: '#93A5BC', fontSize: 11 },
    splitLine: { lineStyle: { color: '#23364F', type: 'dashed' } }
  },
  timeAxis: {
    axisLine: { lineStyle: { color: '#23364F' } },
    axisLabel: { color: '#93A5BC', fontSize: 11 },
    splitLine: { show: false }
  },
  logAxis: {
    axisLine: { lineStyle: { color: '#23364F' } },
    axisLabel: { color: '#93A5BC', fontSize: 11 },
    splitLine: { lineStyle: { color: '#23364F', type: 'dashed' } }
  },
  grid: { left: 12, right: 16, top: 36, bottom: 8, containLabel: true }
})

// WEB-15：亮色主题「山乡站点」（管理端仪表盘/业务图表用）——与 cargo-dark 同源的类别色板。
// 注意：echarts canvas 不继承 CSS 变量，序列色必须是字面 hex（与主题/常量取色，不写 var(--*)）。
echarts.registerTheme('cargo', {
  backgroundColor: 'transparent',
  color: ['#2E7BBF', '#D9A441', '#C75B2A', '#5FBFAF', '#B98FD6', '#7FB3E3', '#E07A3F', '#93A5BC'],
  legend: { textStyle: { color: '#4E4E48', fontSize: 12 } },
  tooltip: { borderWidth: 1 },
  categoryAxis: {
    axisLine: { lineStyle: { color: '#D9D4C7' } },
    axisTick: { show: false },
    axisLabel: { color: '#4E4E48', fontSize: 11 },
    splitLine: { show: false }
  },
  valueAxis: {
    axisLine: { show: false },
    axisTick: { show: false },
    axisLabel: { color: '#4E4E48', fontSize: 11 },
    splitLine: { lineStyle: { color: '#ECE8DD', type: 'dashed' } }
  },
  grid: { left: 12, right: 16, top: 36, bottom: 8, containLabel: true }
})

/** WEB-15：业务图表统一序列色（字面 hex——canvas 不继承 CSS 变量），与 'cargo' 主题保持一致 */
export const CARGO_CHART_COLORS = ['#2E7BBF', '#D9A441', '#C75B2A', '#5FBFAF', '#B98FD6', '#7FB3E3', '#E07A3F', '#93A5BC']

export default echarts
