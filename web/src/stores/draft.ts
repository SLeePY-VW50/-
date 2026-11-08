import { ref } from 'vue'
import { defineStore } from 'pinia'
import type { ReservationDraft } from '../types'

export const useDraftStore = defineStore('reservation-draft', () => {
  const draft = ref<ReservationDraft | null>(null)

  function setDraft(value: ReservationDraft) {
    draft.value = { ...value, preferences: { ...value.preferences } }
  }

  function takeDraft() {
    const value = draft.value
    draft.value = null
    return value
  }

  function clearDraft() {
    draft.value = null
  }

  return { draft, setDraft, takeDraft, clearDraft }
})
