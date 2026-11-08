<script setup lang="ts">
defineProps<{ title?: string; description?: string }>()
</script>

<template>
  <div class="empty-state" role="status">
    <span class="empty-state__mark" aria-hidden="true"><i /><i /><i /><i /></span>
    <strong>{{ title || '暂无数据' }}</strong>
    <p v-if="description">{{ description }}</p>
    <slot />
  </div>
</template>

<style scoped>
.empty-state { display: grid; justify-items: center; gap: 10px; padding: 42px 22px; color: var(--muted); background: #fafbfc; border: 1px dashed #d9dfe9; border-radius: 12px; text-align: center; }
.empty-state__mark { display: grid; grid-template-columns: 1fr 1fr; gap: 3px; width: 40px; height: 40px; margin-bottom: 3px; padding: 9px; background: #eaf0ff; border-radius: 11px; }
.empty-state__mark i { background: #6d8df5; border-radius: 2px; opacity: .75; }
.empty-state__mark i:nth-child(2), .empty-state__mark i:nth-child(3) { opacity: .25; }
.empty-state strong { color: #475467; font-size: 14px; font-weight: 650; }
.empty-state p { max-width: 480px; margin: 0; font-size: 12px; line-height: 1.75; text-wrap: pretty; }
</style>
