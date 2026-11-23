<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import http, { ApiError, errorMessage } from '../../api/http'
import { useAuthStore } from '../../stores/auth'
import type { Lab } from '../../types'
import EmptyState from '../../components/EmptyState.vue'

interface Semester { semesterId: number; code: string; name: string; week1Monday: string; weekCount: number; status: string }
interface RosterMember { userId: number; account: string; name: string }
interface Entry {
  entryId: number
  semesterCode: string
  labId: number
  labName: string
  courseName: string
  teacherAccount: string
  teacherName: string
  className: string
  dayOfWeek: number
  startPeriod: number
  endPeriod: number
  weekStart: number
  weekEnd: number
  sessionCount: number
  roster: RosterMember[]
}
interface Conflict {
  rowNumber: number
  date: string
  weekNo: number
  startPeriod: number
  endPeriod: number
  reservationId: number
  account: string
  userName: string
  purpose: string
  reservationMode: string
  occupancySource: string
}
const weekdays = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日']
const auth = useAuthStore()
const isSysAdmin = computed(() => auth.roles.includes('SYS_ADMIN'))
const semesters = ref<Semester[]>([])
const labs = ref<Lab[]>([])
const entries = ref<Entry[]>([])
const conflicts = ref<Conflict[]>([])
const error = ref('')
const success = ref('')
const loading = ref(false)
const saving = ref(false)
const rosterAccount = ref('')
const rosterEntryId = ref<number | null>(null)
const semesterForm = reactive({ code: '', name: '', week1Monday: '', weekCount: 18 })
const form = reactive({
  semesterId: null as number | null,
  labId: null as number | null,
  courseName: '',
  teacherAccount: '',
  className: '',
  dayOfWeek: 3,
  startPeriod: 1,
  endPeriod: 2,
  weekStart: 1,
  weekEnd: 16,
  students: '',
})

function studentAccounts() {
  return form.students.split(/[,，;；、\s]+/).map((item) => item.trim()).filter(Boolean)
}
function takeConflicts(cause: unknown) {
  conflicts.value = []
  if (cause instanceof ApiError && cause.status === 409 && cause.payload && typeof cause.payload === 'object' && 'conflicts' in cause.payload) {
    conflicts.value = (cause.payload as { conflicts: Conflict[] }).conflicts || []
  }
  error.value = errorMessage(cause)
}
async function load() {
  loading.value = true
  error.value = ''
  try {
    const [semesterRows, labRows, entryRows] = await Promise.all([
      http.get<Semester[]>('/api/timetable/semesters'),
      http.get<Lab[]>('/api/timetable/labs'),
      http.get<Entry[]>('/api/timetable/entries', { params: { semesterId: form.semesterId || undefined, labId: form.labId || undefined } }),
    ])
    semesters.value = semesterRows
    labs.value = labRows
    entries.value = entryRows
    if (!form.semesterId && semesterRows.length) form.semesterId = semesterRows[0].semesterId
    if (!form.labId && labRows.length) form.labId = labRows[0].labId
  } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false }
}
async function createSemester() {
  error.value = ''; success.value = ''; saving.value = true
  try {
    const created = await http.post<Semester>('/api/timetable/semesters', {
      code: semesterForm.code.trim(), name: semesterForm.name.trim(), week1Monday: semesterForm.week1Monday, weekCount: Number(semesterForm.weekCount),
    })
    success.value = `学期 ${created.name} 已保存。第一周周一是 ${created.week1Monday}，共 ${created.weekCount} 周。`
    semesterForm.code = ''; semesterForm.name = ''; semesterForm.week1Monday = ''
    form.semesterId = created.semesterId
    await load()
  } catch (cause) { error.value = errorMessage(cause) } finally { saving.value = false }
}
async function createEntry() {
  error.value = ''; success.value = ''; conflicts.value = []; saving.value = true
  try {
    const created = await http.post<Entry>('/api/timetable/entries', {
      semesterId: Number(form.semesterId), labId: Number(form.labId), courseName: form.courseName.trim(),
      teacherAccount: form.teacherAccount.trim(), className: form.className.trim(),
      dayOfWeek: Number(form.dayOfWeek), startPeriod: Number(form.startPeriod), endPeriod: Number(form.endPeriod),
      weekStart: Number(form.weekStart), weekEnd: Number(form.weekEnd), studentAccounts: studentAccounts(),
    })
    success.value = `${created.courseName} 已占用 ${created.sessionCount} 次整间课。个人预约和排期只能使用其余节次。`
    form.courseName = ''; form.className = ''; form.students = ''
    await load()
  } catch (cause) { takeConflicts(cause) } finally { saving.value = false }
}
async function importFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  error.value = ''; success.value = ''; conflicts.value = []; saving.value = true
  try {
    const body = new FormData()
    body.append('file', file)
    const created = await http.post<Entry[]>('/api/timetable/import', body)
    success.value = `已导入 ${created.length} 条课表。有冲突的文件不会写入任何一行。`
    await load()
  } catch (cause) { takeConflicts(cause) } finally { saving.value = false }
}
async function addRoster(entry: Entry) {
  const account = rosterAccount.value.trim()
  if (!account || rosterEntryId.value !== entry.entryId) { error.value = '请填写要加入的学生账号'; return }
  error.value = ''; success.value = ''
  try {
    await http.post(`/api/timetable/entries/${entry.entryId}/roster`, { studentAccounts: [account] })
    success.value = `${account} 已加入 ${entry.courseName}`
    rosterAccount.value = ''
    await load()
  } catch (cause) { error.value = errorMessage(cause) }
}
async function removeRoster(entry: Entry, member: RosterMember) {
  error.value = ''; success.value = ''
  try {
    await http.delete(`/api/timetable/entries/${entry.entryId}/roster/${member.userId}`)
    success.value = `${member.name} 已移出 ${entry.courseName}`
    await load()
  } catch (cause) { error.value = errorMessage(cause) }
}
onMounted(load)
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div>
        <span class="eyebrow">课表占用</span>
        <h1>学期课表</h1>
        <p>按周次展开后整间写入节次位图。和已有个人预约冲突时只列出冲突、不覆盖。实验室管理员只能维护自己管理的实验室。</p>
      </div>
    </header>
    <div v-if="success" class="success-banner" role="status">{{ success }}</div>
    <div v-if="error" class="error-banner" role="alert">{{ error }}</div>
    <section v-if="conflicts.length" class="panel">
      <div class="panel__header"><div><h2>冲突清单</h2><span class="panel__subtitle">本次没有写入。请先处理下列预约，再重新提交。</span></div></div>
      <div class="table-wrap">
        <table class="data-table">
          <thead><tr><th>来源</th><th>日期</th><th>周次 / 节次</th><th>预约人</th><th>说明</th></tr></thead>
          <tbody>
            <tr v-for="(conflict, index) in conflicts" :key="`${conflict.reservationId}-${index}`">
              <td>{{ conflict.rowNumber ? `第${conflict.rowNumber}行` : '当前条目' }}</td>
              <td class="mono">{{ conflict.date }}</td>
              <td class="mono">第{{ conflict.weekNo }}周 · {{ conflict.startPeriod }}–{{ conflict.endPeriod }}节</td>
              <td>{{ conflict.userName || '—' }} <span class="mono">{{ conflict.account }}</span></td>
              <td>{{ conflict.purpose }} <span v-if="conflict.reservationId" class="mono">#{{ conflict.reservationId }} · {{ conflict.occupancySource }}</span></td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
    <section v-if="isSysAdmin" class="panel">
      <div class="panel__header"><div><h2>配置学期</h2><span class="panel__subtitle">第一周必须是周一。已有课表后不能再改周历。</span></div></div>
      <form class="form-grid" @submit.prevent="createSemester">
        <label class="field"><span>学期代码</span><input v-model="semesterForm.code" maxlength="32" placeholder="2026-2027-1"></label>
        <label class="field"><span>名称</span><input v-model="semesterForm.name" maxlength="128"></label>
        <label class="field"><span>第一周周一</span><input v-model="semesterForm.week1Monday" type="date"></label>
        <label class="field"><span>周数</span><input v-model.number="semesterForm.weekCount" type="number" min="1" max="30"></label>
        <button class="button button--primary" type="submit" :disabled="saving">保存学期</button>
      </form>
    </section>
    <section class="panel">
      <div class="panel__header"><div><h2>录入一条课表</h2><span class="panel__subtitle">人少也占整间。不走提前 1–14 天，也不受 14 条批次上限。</span></div></div>
      <form class="form-grid" @submit.prevent="createEntry">
        <label class="field"><span>学期</span><select v-model.number="form.semesterId"><option v-for="semester in semesters" :key="semester.semesterId" :value="semester.semesterId">{{ semester.name }}（{{ semester.weekCount }}周）</option></select></label>
        <label class="field"><span>实验室</span><select v-model.number="form.labId"><option v-for="lab in labs" :key="lab.labId" :value="lab.labId">{{ lab.name }}</option></select></label>
        <label class="field"><span>课程名</span><input v-model="form.courseName" maxlength="128"></label>
        <label class="field"><span>教师账号</span><input v-model="form.teacherAccount" maxlength="32"></label>
        <label class="field"><span>班级</span><input v-model="form.className" maxlength="128"></label>
        <label class="field"><span>星期</span><select v-model.number="form.dayOfWeek"><option v-for="day in 7" :key="day" :value="day">{{ weekdays[day] }}</option></select></label>
        <label class="field"><span>开始节</span><input v-model.number="form.startPeriod" type="number" min="1"></label>
        <label class="field"><span>结束节</span><input v-model.number="form.endPeriod" type="number" min="1"></label>
        <label class="field"><span>开始周</span><input v-model.number="form.weekStart" type="number" min="1"></label>
        <label class="field"><span>结束周</span><input v-model.number="form.weekEnd" type="number" min="1"></label>
        <label class="field form-span"><span>学生账号</span><textarea v-model="form.students" rows="2" placeholder="逗号或换行分隔，可稍后增减"></textarea></label>
        <button class="button button--primary" type="submit" :disabled="saving">写入课表</button>
      </form>
      <label class="field import-field"><span>Excel 导入（xlsx，表头：学期代码、实验室代码、课程名、教师账号、班级、星期、开始节、结束节、开始周、结束周、学生账号）</span><input type="file" accept=".xlsx" @change="importFile"></label>
    </section>
    <section class="panel">
      <div class="panel__header"><div><h2>已录入课表</h2><span class="panel__subtitle">名单决定谁可以签到。</span></div></div>
      <div v-if="loading" class="loading-line">正在读取课表</div>
      <EmptyState v-else-if="!entries.length" title="还没有课表" description="先配置学期，再按周次、星期和节次录入。" />
      <article v-for="entry in entries" :key="entry.entryId" class="entry">
        <div>
          <strong>{{ entry.courseName }}</strong>
          <span class="mono">{{ entry.className }} · {{ entry.labName }} · {{ weekdays[entry.dayOfWeek] }} 第{{ entry.startPeriod }}–{{ entry.endPeriod }}节 · 第{{ entry.weekStart }}–{{ entry.weekEnd }}周 · {{ entry.sessionCount }}次</span>
          <span>教师 {{ entry.teacherName }}（{{ entry.teacherAccount }}）</span>
        </div>
        <div class="roster">
          <span v-for="member in entry.roster" :key="member.userId" class="tag">{{ member.name }} {{ member.account }} <button type="button" @click="removeRoster(entry, member)">移出</button></span>
          <form class="roster-add" @submit.prevent="addRoster(entry)">
            <input v-model="rosterAccount" placeholder="学生账号" @focus="rosterEntryId = entry.entryId">
            <button class="button button--small" type="submit" @click="rosterEntryId = entry.entryId">加入</button>
          </form>
        </div>
      </article>
    </section>
  </div>
</template>

<style scoped>
.form-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; align-items: end; }
.form-span { grid-column: span 3; }
.import-field { margin-top: 16px; }
.entry { display: grid; gap: 8px; padding: 12px 0; border-top: 1px solid var(--line); }
.entry span { display: block; color: var(--muted); font-size: 13px; }
.roster { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.roster button { margin-left: 6px; border: 0; background: transparent; color: var(--muted); cursor: pointer; }
.roster-add { display: flex; gap: 8px; }
.roster-add input { min-height: 36px; }
@media (max-width: 900px) { .form-grid, .form-span { grid-template-columns: 1fr 1fr; grid-column: auto; } }
</style>
