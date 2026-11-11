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
.empty-state { display: grid; justify-items: start; gap: 8px; padding: 24px 0; color: var(--muted); background: transparent; border: 0; text-align: left; }
.empty-state__mark { display: grid; grid-template-columns: repeat(4, 8px); gap: 4px; align-items: end; height: 22px; margin-bottom: 4px; }
.empty-state__mark i { background: var(--ink); }
.empty-state__mark i:nth-child(1) { height: 4px; opacity: .15; }
.empty-state__mark i:nth-child(2) { height: 16px; }
.empty-state__mark i:nth-child(3) { height: 10px; opacity: .45; }
.empty-state__mark i:nth-child(4) { height: 6px; opacity: .2; }
.empty-state strong { color: var(--ink); font-size: 14px; font-weight: 600; }
.empty-state p { max-width: 460px; margin: 0; font-size: 13px; line-height: 1.65; text-wrap: pretty; }
</style>
