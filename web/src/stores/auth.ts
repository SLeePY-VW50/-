import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { Role, User } from '../types'
import { AUTH_TOKEN_KEY, AUTH_USER_KEY } from '../api/http'

function restoreUser(): User | null {
  const raw = sessionStorage.getItem(AUTH_USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as User
  } catch {
    sessionStorage.removeItem(AUTH_USER_KEY)
    return null
  }
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref(sessionStorage.getItem(AUTH_TOKEN_KEY) || '')
  const user = ref<User | null>(restoreUser())
  const roles = computed<Role[]>(() => user.value?.roles || [])
  const isAuthenticated = computed(() => Boolean(token.value && user.value))

  function setAuth(nextToken: string, nextUser: User) {
    token.value = nextToken
    user.value = nextUser
    sessionStorage.setItem(AUTH_TOKEN_KEY, nextToken)
    sessionStorage.setItem(AUTH_USER_KEY, JSON.stringify(nextUser))
  }

  function logout() {
    token.value = ''
    user.value = null
    sessionStorage.removeItem(AUTH_TOKEN_KEY)
    sessionStorage.removeItem(AUTH_USER_KEY)
  }

  function hasRole(role: Role) {
    return roles.value.includes(role)
  }

  return { token, user, roles, isAuthenticated, setAuth, logout, hasRole }
})
