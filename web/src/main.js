import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './style.css'
import App from './App.vue'
import router from './router'
import { createPinia } from 'pinia'
import { setupInterceptors } from './api/interceptors'

const app = createApp(App)
app.use(createPinia())
app.use(ElementPlus)
app.use(router)
setupInterceptors()
app.mount('#app')
