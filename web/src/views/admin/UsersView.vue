<script setup lang="ts">
import { onMounted, ref } from 'vue'
import http, { errorMessage } from '../../api/http'
import type { User } from '../../types'
import { roleLabels } from '../../utils'
import EmptyState from '../../components/EmptyState.vue'

const users = ref<User[]>([])
const loading = ref(false)
const error = ref('')
const success = ref('')
const query = ref('')
async function load() { loading.value = true; error.value = ''; try { users.value = await http.get<User[]>('/api/admin/users') } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false } }
async function updateStatus(user: User) {
  error.value = ''; success.value = ''
  try { await http.patch(`/api/admin/users/${user.userId}/status`, { status: user.status }); success.value = `用户 ${user.name} 已${user.status === 'ACTIVE' ? '启用' : '停用'}`; await load() } catch (cause) { error.value = errorMessage(cause); await load() }
}
onMounted(load)
</script>

<template>
  <div class="page"><header class="page-header"><div><span class="eyebrow">系统管理 · 用户</span><h1>用户状态管理</h1><p>查看系统真实账号与角色；停用后该账号不能登录。</p></div></header><div v-if="success" class="success-banner" role="status">{{ success }}</div><div v-if="error" class="error-banner" role="alert">{{ error }}</div><section class="panel"><div class="toolbar"><label class="field user-search"><span>筛选账号、姓名或角色</span><input v-model="query" placeholder="输入关键字"></label></div><div v-if="loading" class="loading-line">正在读取用户</div><EmptyState v-else-if="!error && !users.length" title="暂无用户" /><div v-else class="table-wrap"><table class="data-table"><thead><tr><th>用户ID</th><th>账号</th><th>姓名</th><th>角色</th><th>状态</th></tr></thead><tbody><tr v-for="user in users.filter((item) => !query || `${item.account} ${item.name} ${item.roles.map((role) => roleLabels[role]).join(' ')}`.toLowerCase().includes(query.toLowerCase()))" :key="user.userId"><td class="mono">{{ user.userId }}</td><td class="mono">{{ user.account }}</td><td>{{ user.name }}</td><td><div class="button-row"><span v-for="role in user.roles" :key="role" class="tag">{{ roleLabels[role] }}</span></div></td><td><select v-model="user.status" @change="updateStatus(user)"><option value="ACTIVE">正常</option><option value="DISABLED">停用</option></select></td></tr></tbody></table></div></section></div>
</template>

<style scoped>
.user-search { min-width: min(360px, 100%); }select { min-height: 40px; padding: 8px 10px; color: var(--ink); background: var(--paper); border: 1px solid var(--line); border-radius: 0; outline: none; font-size: 14px; }select:focus { border-color: var(--ink); box-shadow: none; }
</style>
