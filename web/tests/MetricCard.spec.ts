import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { markRaw } from 'vue'
import { OfficeBuilding } from '@element-plus/icons-vue'
import MetricCard from '../src/components/MetricCard.vue'

describe('MetricCard', () => {
  it('显示模板图标与accent外观', () => {
    const wrapper = mount(MetricCard, {
      props: { label: '涉及实验室', value: 3, icon: markRaw(OfficeBuilding), accent: 'purple' },
      global: { stubs: { 'el-icon': { template: '<span><slot /></span>' } } },
    })

    expect(wrapper.find('.metric-card__icon.purple').exists()).toBe(true)
    expect(wrapper.text()).toContain('涉及实验室')
    expect(wrapper.text()).toContain('3')
  })
})
