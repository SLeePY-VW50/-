export const chartPalette = {
  pine: '#171717',
  ink: '#171717',
  amber: '#737373',
  muted: '#737373',
  line: '#ececec',
  split: '#ececec',
  paper: '#ffffff',
  wash: '#f4f4f4',
  mid: '#a3a3a3',
} as const

export const chartTooltip = {
  backgroundColor: chartPalette.paper,
  borderColor: chartPalette.line,
  borderWidth: 1,
  textStyle: { color: chartPalette.ink, fontSize: 12 },
  extraCssText: 'border-radius:0;box-shadow:none;',
}

export const chartAxis = {
  axisLine: { lineStyle: { color: chartPalette.line } },
  axisTick: { show: false },
  axisLabel: { color: chartPalette.muted, fontFamily: 'ui-monospace, Consolas, monospace', fontSize: 11, margin: 12 },
}

export const chartSplit = { lineStyle: { color: chartPalette.split, type: 'dashed' as const } }

export const chartLegend = {
  top: 0,
  right: 0,
  icon: 'roundRect' as const,
  itemWidth: 10,
  itemHeight: 10,
  textStyle: { color: chartPalette.muted, fontSize: 12 },
}
