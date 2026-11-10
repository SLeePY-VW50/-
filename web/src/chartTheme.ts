export const chartPalette = {
  pine: '#1f6b57',
  ink: '#1a1d1b',
  amber: '#c4841d',
  muted: '#656d67',
  line: '#e4e0d8',
  split: '#ece7df',
  paper: '#fbfaf7',
  wash: '#e7f3ee',
  mid: '#7eae9e',
} as const

export const chartTooltip = {
  backgroundColor: chartPalette.paper,
  borderColor: chartPalette.line,
  borderWidth: 1,
  textStyle: { color: chartPalette.ink, fontSize: 12 },
  extraCssText: 'border-radius:6px;box-shadow:none;',
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
