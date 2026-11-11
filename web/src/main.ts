import { createApp } from 'vue'
import { createPinia } from 'pinia'
import {
  ElConfigProvider,
  ElDialog,
  ElIcon,
  ElPagination,
  provideGlobalConfig,
} from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/es/components/config-provider/style/css'
import 'element-plus/es/components/dialog/style/css'
import 'element-plus/es/components/icon/style/css'
import 'element-plus/es/components/pagination/style/css'
import './styles.css'
import App from './App.vue'
import router from './router'

const app = createApp(App)
for (const component of [ElConfigProvider, ElDialog, ElIcon, ElPagination]) {
  app.component(component.name!, component)
}
provideGlobalConfig({ locale: zhCn }, app, true)
app.use(createPinia())
app.use(router)
app.mount('#app')
