<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { echarts, type EChartsOption, type EChartsType } from '../charts'
import { chartAxis, chartLegend, chartPalette, chartSplit, chartTooltip } from '../chartTheme'
import { ArrowRight, CircleCheck } from '@element-plus/icons-vue'
import http, { errorMessage } from '../api/http'
import type { DashboardData, Lab, LabRules, Page, Reservation } from '../types'
import { useAuthStore } from '../stores/auth'
import { addDays, percent, reservationTime, statusLabels, timeOnly, toDateInput, today } from '../utils'
import EmptyState from '../components/EmptyState.vue'

const auth = useAuthStore()
const data = ref<DashboardData | null>(null)
const reservations = ref<Reservation[]>([])
const loading = ref(true)
const error = ref('')
const chartElement = ref<HTMLDivElement>()
let chart: EChartsType | null = null

const isOps = computed(() => auth.roles.includes('LAB_ADMIN') || auth.roles.includes('SYS_ADMIN'))
const isLearner = computed(() => !isOps.value && (auth.roles.includes('STUDENT') || auth.roles.includes('TEACHER')))
const canSeeCredit = auth.roles.includes('STUDENT') || auth.roles.includes('TEACHER')
const isSysAdmin = auth.roles.includes('SYS_ADMIN')

const rolePresentation = computed(() => {
  if (auth.roles.includes('SYS_ADMIN')) return { eyebrow: '系统管理', title: '今天要处理的事', description: '先看待排程、使用中和未到场，再看趋势。' }
  if (auth.roles.includes('LAB_ADMIN')) return { eyebrow: '实验室运营', title: '今天要处理的事', description: '先看还没排完和正在进行的预约。' }
  if (auth.roles.includes('TEACHER')) return { eyebrow: '今日实验', title: auth.user?.name || '老师', description: '下一场在左边。其余安排按时间排在后面。' }
  return { eyebrow: '今日实验', title: auth.user?.name || '同学', description: '下一场在左边。到场后从这里去签到。' }
})

const activeStatuses = new Set(['PENDING_SCHEDULE', 'APPROVED', 'IN_USE'])
const upcoming = computed(() => {
  const day = data.value?.date || today()
  return reservations.value
    .filter((item) => activeStatuses.has(item.status) && item.date >= day)
    .sort((a, b) => {
      const left = a.startTime || `${a.date}T${timeOnly(a.windowStart)}`
      const right = b.startTime || `${b.date}T${timeOnly(b.windowStart)}`
      return left.localeCompare(right)
    })
})
const focusReservation = computed(() => upcoming.value[0] || null)
const laterItems = computed(() => upcoming.value.slice(1, 8))
const recentItems = computed(() => {
  const upcomingIds = new Set(upcoming.value.map((item) => item.resvId))
  return reservations.value
    .filter((item) => !upcomingIds.has(item.resvId))
    .sort((a, b) => {
      const left = a.startTime || `${a.date}T${timeOnly(a.windowStart)}`
      const right = b.startTime || `${b.date}T${timeOnly(b.windowStart)}`
      return right.localeCompare(left)
    })
    .slice(0, 4)
})

interface OpenWindow {
  labId: number
  labName: string
  location: string
  date: string
  open: string
  close: string
}

const openWindows = ref<OpenWindow[]>([])

function weekday(date: string) {
  return new Date(`${date}T00:00:00`).getDay() || 7
}

function minutes(value: string) {
  const [hour, minute] = value.split(':').map(Number)
  return hour * 60 + minute
}

function nextOpenWindow(lab: Lab, rules: LabRules, day: string, now = new Date()): OpenWindow | null {
  if (lab.status !== 'ACTIVE') return null
  const todayText = toDateInput(now)
  for (let offset = 0; offset <= 14; offset += 1) {
    const date = addDays(day, offset)
    const rule = rules.weeklyRules.find((item) => item.dayOfWeek === weekday(date))
    if (!rule || offset < rule.advanceMinDays || offset > rule.advanceMaxDays) continue
    const open = timeOnly(rule.openTime)
    const close = timeOnly(rule.closeTime, true)
    const stillAhead = date > todayText || (date === todayText && minutes(close) > now.getHours() * 60 + now.getMinutes())
    if (!stillAhead) continue
    return { labId: lab.labId, labName: lab.name, location: lab.location, date, open, close }
  }
  return null
}

async function readOpenWindows(day: string) {
  try {
    const labs = await http.get<Lab[]>('/api/labs')
    const found = await Promise.all(labs.filter((lab) => lab.status === 'ACTIVE').map(async (lab) => {
      try {
        const rules = await http.get<LabRules>(`/api/labs/${lab.labId}/rules`)
        return nextOpenWindow(lab, rules, day)
      } catch {
        return null
      }
    }))
    return found
      .filter((item): item is OpenWindow => item !== null)
      .sort((a, b) => a.date.localeCompare(b.date) || a.open.localeCompare(b.open) || a.labId - b.labId)
      .slice(0, 4)
  } catch {
    return []
  }
}

const todayCount = computed(() => {
  const day = data.value?.date || today()
  return upcoming.value.filter((item) => item.date === day).length
})
const queueItems = computed(() => reservations.value
  .filter((item) => ['PENDING_SCHEDULE', 'IN_USE', 'NO_SHOW', 'APPROVED'].includes(item.status))
  .slice(0, 6))

const focusJourney = computed(() => {
  const item = focusReservation.value
  if (!item) {
    return [
      { label: '选择实验室', done: false, current: true },
      { label: '提交预约', done: false, current: false },
      { label: '到场签到', done: false, current: false },
    ]
  }
  const approved = ['APPROVED', 'IN_USE', 'COMPLETED'].includes(item.status) || item.status === 'PENDING_SCHEDULE'
  const checkedIn = Boolean(item.checkinTime) || item.status === 'IN_USE' || item.status === 'COMPLETED'
  const started = item.status === 'IN_USE' || item.status === 'COMPLETED'
  return [
    { label: item.status === 'PENDING_SCHEDULE' ? '等待排程' : '预约已确认', done: approved, current: !checkedIn && item.status !== 'PENDING_SCHEDULE' },
    { label: checkedIn ? '已签到' : '到场签到', done: checkedIn, current: approved && !checkedIn && item.status === 'APPROVED' },
    { label: started ? '实验进行中/完成' : '开始实验', done: started, current: checkedIn && !started },
  ]
})

function clock(item: Reservation) {
  if (item.startTime) return timeOnly(item.startTime)
  return timeOnly(item.windowStart)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const tasks: Array<Promise<unknown>> = [http.get<DashboardData>('/api/statistics/dashboard')]
    if (isLearner.value) tasks.push(http.get<Page<Reservation>>('/api/reservations/my', { params: { page: 0, size: 20 } }))
    else tasks.push(http.get<Page<Reservation>>('/api/reservations', { params: { page: 0, size: 12 } }).catch(() => null))
    const [dashboard, extra] = await Promise.all(tasks)
    data.value = dashboard as DashboardData
    const page = extra as Page<Reservation> | null
    reservations.value = Array.isArray(page?.items) ? page.items : []
    openWindows.value = []
    if (isLearner.value && !upcoming.value.length) openWindows.value = await readOpenWindows(data.value.date || today())
    loading.value = false
    await nextTick()
    renderChart()
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}

function renderChart() {
  if (!chartElement.value || !data.value?.trend?.length) return
  chart ||= echarts.init(chartElement.value)
  const trend = data.value.trend
  const option: EChartsOption = {
    animation: false,
    color: [chartPalette.pine, chartPalette.amber],
    grid: { left: 36, right: 8, top: 28, bottom: 24 },
    legend: { ...chartLegend, data: ['预约数', '未到场数'] },
    tooltip: { ...chartTooltip, trigger: 'axis' },
    xAxis: { type: 'category', data: trend.map((item) => item.date.slice(5)), ...chartAxis },
    yAxis: { type: 'value', minInterval: 1, axisLabel: { color: chartPalette.muted, fontSize: 11, fontFamily: 'ui-monospace, Consolas, monospace' }, splitLine: chartSplit },
    series: [
      { name: '预约数', type: 'bar', barMaxWidth: 14, data: trend.map((item) => item.reservationCount), itemStyle: { borderRadius: [2, 2, 0, 0], color: chartPalette.pine } },
      { name: '未到场数', type: 'line', data: trend.map((item) => item.noShowCount), smooth: false, symbol: 'circle', symbolSize: 5, lineStyle: { width: 1.5, color: chartPalette.amber }, itemStyle: { color: chartPalette.amber } },
    ],
  }
  chart.setOption(option, true)
}

const resize = () => chart?.resize()
onMounted(() => { load(); window.addEventListener('resize', resize) })
onBeforeUnmount(() => { window.removeEventListener('resize', resize); chart?.dispose() })
</script>

<template>
  <div class="home">
    <header class="home__intro">
      <div>
        <span class="eyebrow">{{ rolePresentation.eyebrow }}<template v-if="data"> · {{ data.date }}</template></span>
        <h1>{{ rolePresentation.title }}</h1>
        <p>{{ rolePresentation.description }}</p>
      </div>
    </header>

    <div v-if="error" class="error-banner home__alert" role="alert">{{ error }} <button class="button button--small" type="button" @click="load">重新加载</button></div>
    <div v-if="loading" class="home__loading" aria-busy="true"><p class="sr-only">正在读取工作台</p><span /><span /></div>

    <div v-else-if="data && isLearner" class="day">
      <section class="day__main">
        <article class="session">
          <p class="session__kicker">下一场实验</p>
          <template v-if="focusReservation">
            <p class="session__time mono">{{ clock(focusReservation) }}</p>
            <h2>{{ focusReservation.labName || '实验室待排程分配' }}</h2>
            <p class="session__meta">{{ reservationTime(focusReservation) }} · {{ statusLabels[focusReservation.status] }} · {{ focusReservation.attendeeCount }} 人</p>
            <p class="session__purpose">{{ focusReservation.purpose }}</p>
            <ol class="path" aria-label="实验进度">
              <li v-for="(step, index) in focusJourney" :key="step.label" :class="{ done: step.done, current: step.current }">
                <span><el-icon v-if="step.done" :size="14"><CircleCheck /></el-icon><template v-else>{{ index + 1 }}</template></span>
                {{ step.label }}
              </li>
            </ol>
            <div class="session__actions">
              <router-link v-if="focusReservation.status === 'APPROVED' && !focusReservation.checkinTime" class="button button--primary" to="/terminal">去终端签到<el-icon><ArrowRight /></el-icon></router-link>
              <router-link v-else class="button button--primary" to="/labs">去时间尺<el-icon><ArrowRight /></el-icon></router-link>
              <router-link class="button" to="/reservations">预约详情</router-link>
            </div>
          </template>
          <EmptyState v-else class="session__guide" title="今天还没有下一场" description="从时间尺选一段开放时段，提交后会出现在这里。">
            <ol class="path" aria-label="预约步骤">
              <li v-for="(step, index) in focusJourney" :key="step.label" :class="{ current: step.current }">
                <span>{{ index + 1 }}</span>
                {{ step.label }}
              </li>
            </ol>
            <router-link class="button button--primary" to="/labs">去时间尺预约<el-icon><ArrowRight /></el-icon></router-link>
          </EmptyState>
        </article>

        <section v-if="focusReservation" class="timeline" aria-labelledby="later-title">
          <h2 id="later-title">随后</h2>
          <p v-if="!laterItems.length" class="timeline__empty">没有更晚的待执行预约。</p>
          <ol v-else>
            <li v-for="item in laterItems" :key="item.resvId">
              <time class="mono">{{ clock(item) }}</time>
              <div>
                <strong>{{ item.labName || '待分配实验室' }}</strong>
                <span>{{ item.date }} · {{ statusLabels[item.status] }}</span>
              </div>
            </li>
          </ol>
        </section>
        <section v-else class="timeline" aria-label="近期安排">
          <template v-if="recentItems.length">
            <h2>近期预约</h2>
            <ol>
              <li v-for="item in recentItems" :key="item.resvId">
                <time class="mono">{{ clock(item) }}</time>
                <div>
                  <strong>{{ item.labName || '待分配实验室' }}</strong>
                  <span>{{ item.date }} · {{ statusLabels[item.status] }}</span>
                </div>
              </li>
            </ol>
          </template>
          <template v-if="openWindows.length">
            <h2>即将开放</h2>
            <p class="timeline__note">下一个可预约日的开放时段，空位以时间尺为准。</p>
            <ol>
              <li v-for="item in openWindows" :key="`${item.labId}-${item.date}`">
                <time class="mono">{{ item.open }}</time>
                <div>
                  <strong>{{ item.labName }}</strong>
                  <span>{{ item.date }} · {{ item.open }}–{{ item.close }}<template v-if="item.location"> · {{ item.location }}</template></span>
                </div>
              </li>
            </ol>
          </template>
          <p v-if="!recentItems.length && !openWindows.length" class="timeline__empty">近期没有预约记录，也没有读到开放时段。</p>
        </section>
      </section>

      <aside class="day__side">
        <dl class="tally">
          <div><dt>今日场次</dt><dd class="mono">{{ todayCount }}</dd></div>
          <div><dt>待执行</dt><dd class="mono">{{ upcoming.length }}</dd></div>
          <div v-if="canSeeCredit"><dt>信用分</dt><dd class="mono">{{ data.creditScore }}</dd></div>
        </dl>
        <div v-if="data.trend?.length" class="side-chart">
          <h2>近 7 天</h2>
          <div ref="chartElement" class="chart" role="img" :aria-label="`近7天预约趋势，共 ${data.trend.length} 个数据点`" />
        </div>
        <nav class="side-links" aria-label="相关入口">
          <router-link to="/labs">时间尺<el-icon><ArrowRight /></el-icon></router-link>
          <router-link to="/reservations">全部预约<el-icon><ArrowRight /></el-icon></router-link>
          <router-link to="/assistant">AI 草稿<el-icon><ArrowRight /></el-icon></router-link>
          <router-link to="/credit">信用与申诉<el-icon><ArrowRight /></el-icon></router-link>
        </nav>
      </aside>
    </div>

    <div v-else-if="data" class="desk">
      <section class="desk__queue" aria-labelledby="queue-title">
        <h2 id="queue-title">队列</h2>
        <router-link class="queue" to="/schedules">
          <span>待排程</span>
          <strong class="mono">{{ data.pendingScheduleCount }}</strong>
          <em>打开排程<el-icon><ArrowRight /></el-icon></em>
        </router-link>
        <router-link class="queue" to="/reservations">
          <span>使用中</span>
          <strong class="mono">{{ data.inUseCount }}</strong>
          <em>打开预约<el-icon><ArrowRight /></el-icon></em>
        </router-link>
        <router-link class="queue" to="/reservations">
          <span>未到场</span>
          <strong class="mono">{{ data.noShowCount }}</strong>
          <em>核对记录<el-icon><ArrowRight /></el-icon></em>
        </router-link>
        <router-link v-if="isSysAdmin" class="queue queue--link" to="/admin/appeals">
          <span>申诉核定</span>
          <strong>去处理</strong>
          <em>打开列表<el-icon><ArrowRight /></el-icon></em>
        </router-link>
        <ol v-if="queueItems.length" class="queue-list">
          <li v-for="item in queueItems" :key="item.resvId">
            <time class="mono">{{ clock(item) }}</time>
            <div>
              <strong>{{ item.labName || '待分配' }}</strong>
              <span>#{{ item.resvId }} · {{ statusLabels[item.status] }} · {{ item.date }}</span>
            </div>
          </li>
        </ol>
        <EmptyState v-else title="队列里没有可展开的预约" description="上面的数字来自今日统计。产生待排程或使用中的记录后，会列在这里。" />
      </section>
      <aside class="desk__side">
        <dl class="tally">
          <div><dt>实验室</dt><dd class="mono">{{ data.labCount }}</dd></div>
          <div><dt>今日预约</dt><dd class="mono">{{ data.reservationCount }}</dd></div>
          <div><dt>已批准</dt><dd class="mono">{{ data.approvedCount }}</dd></div>
          <div><dt>计划利用率</dt><dd class="mono">{{ percent(data.plannedUtilization) }}</dd></div>
          <div><dt>实际利用率</dt><dd class="mono">{{ percent(data.actualUtilization) }}</dd></div>
        </dl>
        <div v-if="data.trend?.length" class="side-chart">
          <h2>近 7 天</h2>
          <div ref="chartElement" class="chart" role="img" :aria-label="`近7天预约趋势，共 ${data.trend.length} 个数据点`" />
        </div>
        <nav class="side-links" aria-label="相关入口">
          <router-link to="/labs">时间尺<el-icon><ArrowRight /></el-icon></router-link>
          <router-link to="/schedules">排程任务<el-icon><ArrowRight /></el-icon></router-link>
          <router-link to="/statistics">使用统计<el-icon><ArrowRight /></el-icon></router-link>
        </nav>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.home__intro { padding: 22px 32px 8px; }
.home__intro h1 { margin: 8px 0 6px; font-size: clamp(28px, 3vw, 40px); }
.home__intro p { margin: 0; color: var(--body); font-size: 14px; }
.home__alert { margin: 12px 32px; }
.home__loading { display: grid; grid-template-columns: 1.6fr .8fr; gap: 24px; padding: 20px 32px; }
.home__loading span { min-height: 280px; background: #efece6; border-radius: 6px; }
.day, .desk { display: grid; grid-template-columns: minmax(0, 1.7fr) minmax(260px, .75fr); gap: 0; align-items: start; border-top: 1px solid var(--line); }
.day__main, .desk__queue { padding: 8px 32px 40px; border-right: 1px solid var(--line); }
.day__side, .desk__side { padding: 20px 24px 40px; }
.session { padding: 8px 0 28px; border-bottom: 1px solid var(--line); }
.session__kicker { margin: 0; color: var(--primary); font-size: 13px; font-weight: 650; letter-spacing: .04em; }
.session__time { margin: 8px 0 0; font-size: clamp(56px, 7vw, 84px); line-height: .9; letter-spacing: -.05em; }
.session h2 { margin: 10px 0 8px; font-size: 28px; }
.session :deep(.session__guide) { justify-items: start; margin-top: 16px; text-align: left; }
.session :deep(.session__guide strong) { font-size: 28px; font-weight: 700; letter-spacing: -.03em; }
.session :deep(.empty-state__mark i:nth-child(1)),
.session :deep(.empty-state__mark i:nth-child(2)),
.session :deep(.empty-state__mark i:nth-child(3)),
.session :deep(.empty-state__mark i:nth-child(4)) { background: var(--primary); }
.session :deep(.empty-state__mark i:nth-child(1)) { opacity: .38; }
.session :deep(.empty-state__mark i:nth-child(2)) { opacity: 1; }
.session :deep(.empty-state__mark i:nth-child(3)) { opacity: .68; }
.session :deep(.empty-state__mark i:nth-child(4)) { opacity: .48; }
.session__meta, .session__purpose { margin: 0; color: var(--body); font-size: 14px; line-height: 1.6; }
.session__purpose { margin-top: 6px; max-width: 46ch; }
.path { display: flex; flex-wrap: wrap; gap: 8px; margin: 18px 0 0; padding: 0; list-style: none; }
.path li { display: inline-flex; align-items: center; gap: 8px; min-height: 36px; color: var(--muted); font-size: 13px; }
.path li span { display: grid; place-items: center; width: 22px; height: 22px; border: 1px solid var(--line); border-radius: 50%; font-size: 11px; }
.path li.done { color: #185546; }
.path li.current { color: var(--primary); font-weight: 650; }
.path li.current span { border-color: var(--primary); }
.session__actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 18px; }
.timeline { padding-top: 22px; }
.timeline h2, .side-chart h2, .desk__queue > h2 { margin: 0 0 12px; font-size: 13px; letter-spacing: .06em; text-transform: none; color: var(--muted); font-weight: 650; }
.timeline__empty { margin: 0; color: var(--muted); font-size: 14px; }
.timeline__note { max-width: 52ch; margin: 0 0 4px; color: var(--muted); font-size: 12px; line-height: 1.6; }
.timeline h2:not(:first-child) { margin-top: 22px; }
.timeline ol, .queue-list { margin: 0; padding: 0; list-style: none; }
.timeline li, .queue-list li { display: grid; grid-template-columns: 64px minmax(0, 1fr); gap: 12px; padding: 12px 0; border-top: 1px solid var(--line); }
.timeline time, .queue-list time { color: var(--ink); font-size: 14px; }
.timeline strong, .queue-list strong, .timeline span, .queue-list span { display: block; }
.timeline span, .queue-list span { margin-top: 2px; color: var(--muted); font-size: 12px; }
.tally { display: grid; margin: 0 0 18px; }
.tally div { display: flex; justify-content: space-between; gap: 12px; padding: 10px 0; border-bottom: 1px solid var(--line); }
.tally dt { color: var(--body); font-size: 13px; }
.tally dd { margin: 0; font-size: 18px; }
.side-chart .chart { height: 180px; }
.side-links { display: grid; margin-top: 8px; }
.side-links a, .queue { display: flex; align-items: center; justify-content: space-between; gap: 8px; min-height: 44px; color: var(--ink); border-bottom: 1px solid var(--line); font-size: 14px; font-weight: 600; }
.side-links a:hover, .queue:hover { color: var(--primary); text-decoration: none; }
.queue { display: grid; grid-template-columns: 1fr auto auto; padding: 14px 0; }
.queue strong { font-size: 28px; font-weight: 700; letter-spacing: -.04em; }
.queue em { display: inline-flex; align-items: center; gap: 4px; color: var(--muted); font-style: normal; font-size: 13px; font-weight: 600; }
.queue--link strong { font-size: 16px; letter-spacing: 0; }
.queue-list { margin-top: 8px; }
@media (max-width: 960px) {
  .day, .desk, .home__loading { grid-template-columns: 1fr; }
  .day__main, .desk__queue { border-right: 0; border-bottom: 1px solid var(--line); }
  .home__intro, .day__main, .desk__queue { padding-inline: 16px; }
  .home__alert { margin-inline: 16px; }
  .session__time { font-size: 56px; }
}
</style>
