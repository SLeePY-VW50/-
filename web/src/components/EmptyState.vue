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
.empty-state { display: grid; justify-items: center; gap: 8px; padding: 36px 20px; color: var(--muted); background: #f7f4ee; border: 1px dashed var(--line); border-radius: 8px; text-align: center; }
.empty-state__mark { display: grid; grid-template-columns: repeat(4, 6px); gap: 3px; align-items: end; height: 22px; margin-bottom: 4px; }
.empty-state__mark i { background: #d9d3c8; border-radius: 1px; }
.empty-state__mark i:nth-child(1) { height: 8px; }
.empty-state__mark i:nth-child(2) { height: 16px; background: var(--primary); }
.empty-state__mark i:nth-child(3) { height: 12px; background: #9dbaae; }
.empty-state__mark i:nth-child(4) { height: 6px; }
.empty-state strong { color: var(--ink); font-size: 14px; font-weight: 600; }
.empty-state p { max-width: 460px; margin: 0; font-size: 13px; line-height: 1.65; text-wrap: pretty; }
</style>
