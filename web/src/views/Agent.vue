<template>
  <main class="agent-list-page agent-workbench">
    <header class="agent-list-heading"><div><span class="aw-eyebrow">AGENTS · 智能体</span><h1>智能体</h1><p>将团队知识与模型连接，构建专属的协作助手。</p></div><div class="aw-actions"><RouterLink v-if="admin" to="/models" class="aw-button">模型配置</RouterLink><button v-if="admin" class="aw-button primary" data-testid="create-agent" @click="showCreate = true">＋ 创建智能体</button></div></header>
    <section class="agent-list-metrics" aria-label="智能体概览"><span>全部 <b>{{ metrics.all }}</b></span><span>已发布 <b>{{ metrics.published }}</b></span><span v-if="admin">本周调试 <b>{{ metrics.debuggedThisWeek }}</b></span><span v-if="admin">有暂存 <b>{{ metrics.draftChanged }}</b></span></section>
    <section class="agent-list-panel workspace-panel" aria-label="智能体列表">
      <div class="agent-list-toolbar"><form class="agent-list-search" @submit.prevent="search"><input v-model="keyword" aria-label="搜索智能体" placeholder="输入名称或描述关键词…" /><input v-model="tag" aria-label="标签筛选" placeholder="标签" /><button class="aw-button" type="submit">搜索</button></form><nav class="agent-list-filters" aria-label="发布状态"><button v-for="filter in filters" :key="filter.value" :class="{ active: status === filter.value }" @click="status = filter.value; search()">{{ filter.label }}</button></nav></div>
      <div v-if="error" class="aw-inline-error" role="alert">{{ error }} <button class="aw-text-button" @click="load">重试</button></div>
      <div v-if="loading" class="aw-empty">正在载入智能体…</div>
      <div v-else class="agent-list-grid">
        <article v-for="agent in agents" :key="agent.id" class="agent-list-card">
          <RouterLink :to="`/agent/${agent.id}`" class="agent-card-link"><div class="agent-card-top"><span class="aw-avatar">{{ agent.name.slice(0, 1) }}</span><span class="aw-chip" :class="{ sand: agent.status !== 'PUBLISHED' }">{{ label(agent.status) }}</span></div><h2>{{ agent.name }}</h2><p>{{ agent.description || '暂未添加描述' }}</p></RouterLink>
          <div class="agent-card-tags"><button v-for="item in agent.tags" :key="item" class="aw-chip" @click="tag = item; search()">{{ item }}</button><small v-if="admin && !agent.modelConfigured">待配置模型</small></div>
          <footer><span>▦ {{ agent.knowledgeCount }} 知识库</span><span v-if="agent.currentVersion">v{{ agent.currentVersion }}</span><small>{{ agent.lastDebuggedAt ? `调试于 ${date(agent.lastDebuggedAt)}` : '尚未调试' }}</small><button v-if="admin" class="aw-text-button danger" :aria-label="`删除智能体 ${agent.name}`" @click="deleteTarget = agent">删除</button></footer>
        </article>
        <button v-if="admin" class="agent-list-card agent-list-card--new" @click="showCreate = true"><b>＋</b><span>从零开始创建你的智能体</span><small>配置模型、知识库与提示词</small></button>
        <p v-if="!agents.length" class="aw-empty">{{ keyword || tag || status ? '没有匹配的智能体，试试调整筛选条件。' : admin ? '还没有智能体，点击创建开始配置。' : '暂无已发布的智能体，请联系管理员。' }}</p>
      </div>
      <footer class="agent-list-pagination"><span>共 {{ total }} 个智能体</span><div class="aw-actions"><button class="aw-button" :disabled="page <= 1 || loading" @click="page--; load()">上一页</button><span>{{ page }} / {{ Math.max(1, Math.ceil(total / pageSize)) }}</span><button class="aw-button" :disabled="page * pageSize >= total || loading" @click="page++; load()">下一页</button></div></footer>
    </section>
    <div v-if="showCreate" class="aw-modal-mask" @click.self="!busy && (showCreate = false)"><form class="aw-modal" role="dialog" aria-modal="true" aria-label="创建智能体" @submit.prevent="create"><h2>创建智能体</h2><label>名称<input v-model.trim="form.name" required maxlength="32" placeholder="如：新员工客服助理" /></label><label>一句话描述<input v-model="form.description" maxlength="256" placeholder="这个智能体能帮团队做什么？" /></label><p class="aw-help">创建后选择已配置模型，再编写提示词并关联知识库。</p><p v-if="actionError" class="aw-error" role="alert">{{ actionError }}</p><footer><button class="aw-button" type="button" :disabled="busy" @click="showCreate = false">取消</button><button class="aw-button primary" :disabled="busy || !form.name">{{ busy ? '创建中…' : '创建并配置' }}</button></footer></form></div>
    <div v-if="deleteTarget" class="aw-modal-mask"><section class="aw-modal" role="dialog" aria-modal="true" aria-label="删除智能体"><h2>删除「{{ deleteTarget.name }}」？</h2><p>该智能体及其模型映射、发布快照将一起软删除，当前调试会话也会清理。团队将无法继续调用。</p><p v-if="actionError" class="aw-error">{{ actionError }}</p><footer><button class="aw-button" :disabled="busy" @click="deleteTarget = null">取消</button><button class="aw-button sand" :disabled="busy" @click="remove">{{ busy ? '删除中…' : '确认删除' }}</button></footer></section></div>
  </main>
</template>
<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { agentMetrics, createAgent, deleteAgent, errorMessage, listAgents } from '../api/agents'
import '../components/agent/workbench.css'
const auth = useAuthStore(), router = useRouter(), admin = computed(() => auth.user?.role === 'tenant_admin')
const agents = ref([]), metrics = ref({ all: 0, published: 0, debuggedThisWeek: 0, draftChanged: 0 }), total = ref(0)
const keyword = ref(''), tag = ref(''), status = ref(''), page = ref(1), pageSize = 12
const loading = ref(true), error = ref(''), showCreate = ref(false), busy = ref(false), actionError = ref(''), deleteTarget = ref(null)
const form = reactive({ name: '', description: '' }); let generation = 0
const filters = computed(() => admin.value ? [{ value: '', label: '全部' }, { value: 'PUBLISHED', label: '已发布' }, { value: 'DRAFT_CHANGED', label: '有暂存' }, { value: 'UNPUBLISHED', label: '未发布' }] : [{ value: '', label: '全部已发布' }])
const label = status => ({ PUBLISHED: '已发布', DRAFT_CHANGED: '有暂存', UNPUBLISHED: '未发布' }[status] || '未发布')
const date = value => new Date(value).toLocaleDateString('zh-CN')
async function load() {
  const current = ++generation; loading.value = true; error.value = ''
  try {
    const [result, counts] = await Promise.all([listAgents({ page: page.value, pageSize, keyword: keyword.value.trim() || undefined, tag: tag.value.trim() || undefined, status: status.value || undefined }), agentMetrics()])
    if (current !== generation) return
    agents.value = result.items; total.value = result.total; metrics.value = counts
  } catch (e) { if (current === generation) error.value = errorMessage(e) }
  finally { if (current === generation) loading.value = false }
}
function search() { page.value = 1; void load() }
async function create() { busy.value = true; actionError.value = ''; try { const agent = await createAgent({ ...form, systemPrompt: '你是团队的智能助手。请根据用户问题和提供的知识资料，准确、清晰地回答；没有依据时明确说明。' }); showCreate.value = false; await router.push(`/agent/${agent.id}`) } catch (e) { actionError.value = errorMessage(e) } finally { busy.value = false } }
async function remove() { busy.value = true; actionError.value = ''; try { await deleteAgent(deleteTarget.value.id); deleteTarget.value = null; if (agents.value.length === 1 && page.value > 1) page.value--; await load() } catch (e) { actionError.value = errorMessage(e) } finally { busy.value = false } }
watch([showCreate, deleteTarget], () => { actionError.value = '' })
onMounted(load); onBeforeUnmount(() => { generation++ })
</script>
<style scoped>
.agent-list-heading { display: flex; justify-content: space-between; align-items: flex-end; gap: 16px; margin-bottom: 22px; }.agent-list-heading h1 { margin: 8px 0; font-size: 28px; }.agent-list-heading p { margin: 0; font-size: 13px; color: var(--sea-muted); }
.agent-list-metrics { display: flex; gap: 24px; flex-wrap: wrap; margin: 20px 0; color: var(--sea-muted); font-size: 12px; }.agent-list-metrics b { color: var(--sea-deep); font: 500 18px 'JetBrains Mono', monospace; margin-left: 8px; }
.agent-list-panel { padding: 18px; }.agent-list-toolbar { display: flex; align-items: center; gap: 16px; justify-content: space-between; flex-wrap: wrap; border-bottom: 1px solid #e8eff2; padding-bottom: 18px; }.agent-list-search { display: flex; gap: 8px; flex: 1; min-width: 250px; }.agent-list-search input:first-child { flex: 1; }.agent-list-search input:nth-child(2) { width: 90px; }.agent-list-filters { display: flex; gap: 4px; }.agent-list-filters button { border: 0; background: transparent; color: var(--sea-muted); padding: 8px 10px; font: inherit; font-size: 12px; cursor: pointer; border-radius: 6px; }.agent-list-filters .active { color: var(--sea-ink); background: color-mix(in srgb, var(--sea-signal) 12%, var(--sea-paper)); font-weight: 700; }
.agent-list-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; padding: 18px 0; }.agent-list-card { display: flex; flex-direction: column; min-width: 0; border: 1px solid #dfe8ec; border-radius: 10px; padding: 16px; background: var(--sea-paper); }.agent-list-card:hover { border-color: var(--sea-signal); box-shadow: 0 8px 20px #00a6a614; }.agent-card-link { color: inherit; text-decoration: none; }.agent-card-top { display: flex; align-items: center; justify-content: space-between; gap: 8px; }.agent-card-link h2 { margin: 16px 0 8px; font-size: 16px; overflow-wrap: anywhere; }.agent-card-link p { color: var(--sea-muted); font-size: 12px; line-height: 1.8; min-height: 44px; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }.agent-card-tags { display: flex; gap: 6px; flex-wrap: wrap; align-items: center; margin: 10px 0 14px; }.agent-card-tags button { border: 0; cursor: pointer; }.agent-card-tags small { font-size: 11px; color: var(--sea-muted); }.agent-list-card footer { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-top: auto; border-top: 1px solid #e8eff2; padding-top: 10px; font-size: 11px; color: var(--sea-muted); }.agent-list-card footer small { font-size: 10px; }.agent-list-card footer button { margin-left: auto; }.agent-list-card--new { min-height: 238px; justify-content: center; align-items: center; gap: 10px; border-style: dashed; cursor: pointer; color: var(--sea-muted); font: inherit; font-size: 13px; }.agent-list-card--new b { font-size: 32px; color: var(--sea-signal); font-weight: 400; }.agent-list-card--new small { font-size: 11px; }.agent-list-pagination { display: flex; justify-content: space-between; align-items: center; gap: 10px; color: var(--sea-muted); font-size: 12px; }
@media(max-width: 1300px) { .agent-list-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } } @media(max-width: 680px) { .agent-list-heading { flex-direction: column; align-items: flex-start; }.agent-list-grid { grid-template-columns: minmax(0, 1fr); }.agent-list-panel { padding: 12px; }.agent-list-search { min-width: 0; width: 100%; flex-wrap: wrap; }.agent-list-search input:first-child { flex-basis: 100%; }.agent-list-filters button { padding: 7px; }.agent-list-pagination { flex-direction: column; }.agent-list-metrics { gap: 12px; } }
</style>
<style scoped>
@media(max-width: 680px) { .agent-list-search { flex: 0 0 100%; }.agent-list-search input:first-child { width: 100%; }.agent-list-search input:nth-child(2) { flex: 1; } }
</style>
