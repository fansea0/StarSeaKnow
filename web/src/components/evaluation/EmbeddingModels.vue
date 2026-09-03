<script setup>
import { onMounted, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import * as api from '../../api/embeddingEvaluation'
import { errorText, modelName } from './evaluationState'
import './evaluation.css'
const models = ref([]),
  loading = ref(false),
  busy = ref(false),
  error = ref(''),
  message = ref(''),
  editing = ref(null),
  form = ref(null),
  verified = ref(null),
  optionsText = ref(''),
  keepAlive = ref('')
const empty = () => ({ displayName: '', baseUrl: '', modelName: '', queryPrefix: '', documentPrefix: '' })
async function load() {
  loading.value = true
  error.value = ''
  try {
    models.value = await api.listModels()
  } catch (cause) {
    error.value = errorText(cause)
  } finally {
    loading.value = false
  }
}
function edit(model) {
  if (model?.readOnly || model?.id === 'current') return
  editing.value = model?.id || null
  form.value = model
    ? {
        ...empty(),
        ...Object.fromEntries(Object.keys(empty()).map((k) => [k, model[k] || ''])),
        revision: model.revision,
      }
    : empty()
  error.value = ''
  message.value = ''
  verified.value = null
  optionsText.value = model?.options ? JSON.stringify(model.options, null, 2) : ''
  keepAlive.value = model?.keepAlive || ''
}
function command() {
  const value = { ...form.value }
  if (optionsText.value.trim()) {
    const options = JSON.parse(optionsText.value)
    if (!options || Array.isArray(options) || typeof options !== 'object') throw new Error('运行参数必须是 JSON 对象。')
    value.options = options
  }
  if (keepAlive.value.trim()) value.keepAlive = keepAlive.value.trim()
  return value
}
function verifiedTime(value) {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}
watch([optionsText, keepAlive], () => { verified.value = null })
watch(
  form,
  () => {
    verified.value = null
  },
  { deep: true },
)
function valid() {
  if (!form.value.displayName.trim() || !form.value.baseUrl.trim() || !form.value.modelName.trim())
    return '请填写显示名称、服务地址和完整模型名。'
  try {
    const url = new URL(form.value.baseUrl)
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password)
      return '请填写不含账号密码的 HTTP(S) 服务地址。'
  } catch {
    return '请填写完整的 HTTP(S) 服务地址。'
  }
  return ''
}
async function test() {
  error.value = valid()
  if (error.value) return
  busy.value = true
  try {
    verified.value = await api.testModel(command())
  } catch (cause) {
    error.value = errorText(cause)
  } finally {
    busy.value = false
  }
}
async function save() {
  error.value = valid()
  if (error.value) return
  busy.value = true
  try {
    if (editing.value) await api.updateModel(editing.value, command())
    else await api.createModel(command())
    form.value = null
    message.value = '模型配置已保存。历史运行保留原始版本。'
    await load()
  } catch (cause) {
    error.value = errorText(cause)
  } finally {
    busy.value = false
  }
}
async function remove(model) {
  if (model.readOnly || model.id === 'current') return
  try {
    await ElMessageBox.confirm(`删除“${model.displayName}”？已有运行仍保留配置快照。`, '删除向量模型', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  busy.value = true
  error.value = ''
  try {
    await api.deleteModel(model.id)
    await load()
  } catch (cause) {
    error.value = errorText(cause)
  } finally {
    busy.value = false
  }
}
onMounted(load)
</script>
<template>
  <section class="evaluation ev-stack" aria-label="向量模型配置">
    <header class="ev-head">
      <div>
        <h2>向量模型</h2>
        <p class="ev-muted">每条配置独立记录编码前缀与版本，供知识库检索评测使用。</p>
      </div>
      <button class="ev-button ev-primary" data-testid="add-embedding" :disabled="busy" @click="edit()">
        添加向量模型
      </button>
    </header>
    <p v-if="error" class="ev-notice ev-error" role="alert">
      {{ error }} <button v-if="!form" class="ev-button" @click="load">重新加载</button>
    </p>
    <p v-if="message" class="ev-notice" role="status">{{ message }}</p>
    <p v-if="loading" role="status">正在加载向量模型…</p>
    <form v-if="form" class="ev-panel ev-stack" data-testid="embedding-form" @submit.prevent="save">
      <h3>{{ editing ? '编辑向量模型 · 保存为新版本' : '添加向量模型' }}</h3>
      <fieldset class="ev-stack" :disabled="busy">
        <div class="ev-fields">
          <label>显示名称<input v-model="form.displayName" name="displayName" required /></label
          ><label
            >Ollama 服务地址<input
              v-model="form.baseUrl"
              name="baseUrl"
              type="url"
              required
              placeholder="http://localhost:11434"
            /><span class="ev-muted">填写后端能够访问的地址。</span></label
          ><label
            >完整模型名及 tag<input
              v-model="form.modelName"
              name="modelName"
              required
              placeholder="model:tag"
          /></label>
        </div>
        <details>
          <summary>高级设置 · 编码前缀与运行参数</summary>
          <div class="ev-fields">
            <label
              >Query 前缀<textarea
                v-model="form.queryPrefix"
                rows="2"
                placeholder="留空表示不添加前缀"
              /></label
            ><label
              >Document 前缀<textarea
                v-model="form.documentPrefix"
                rows="2"
                placeholder="留空表示不添加前缀"
              />
            </label>
          </div>
          <p class="ev-muted">前缀中的空格和换行会原样保留。验证与运行不自动改写前缀。</p>
          <div class="ev-fields">
            <label>运行参数（JSON，可选）<textarea v-model="optionsText" name="runtimeOptions" rows="3" placeholder='{"num_ctx": 512}' /></label>
            <label>模型保活时间（可选）<input v-model="keepAlive" name="keepAlive" placeholder="例如 5m，留空使用 Ollama 默认值" /></label>
          </div>
        </details>
        <div v-if="verified" class="ev-notice" role="status">
          验证成功 · {{ verified.dimensions ?? '未知' }} 维<code>
            · {{ verified.digest || '未取得 digest，正式验收证据不足' }}</code
          >
        </div>
        <div class="ev-actions">
          <button type="button" class="ev-button" @click="form = null">取消</button
          ><button type="button" class="ev-button" data-testid="test-embedding" @click="test">
            验证 embedding 能力</button
          ><button type="submit" class="ev-button ev-primary">保存模型</button>
        </div>
      </fieldset>
      <p v-if="busy" role="status">正在连接模型服务…</p>
    </form>
    <div class="ev-table-wrap">
      <table class="ev-table">
        <thead>
          <tr>
            <th>模型 / 版本</th>
            <th>维度 / 验证</th>
            <th>来源 / 身份</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="model in models" :key="model.id">
            <td>
              <strong>{{ modelName(model) }}</strong>
              <p>
                <code>{{ model.modelName }} · v{{ model.revision }}</code>
              </p>
              <details>
                <summary>连接与编码配置</summary>
                <p>{{ model.baseUrl }}</p>
                <pre>
Query: {{ model.queryPrefix || '（空）' }}
Document: {{ model.documentPrefix || '（空）' }}</pre
                >
                <pre v-if="model.options && Object.keys(model.options).length">{{ JSON.stringify(model.options, null, 2) }}</pre>
                <p v-if="model.keepAlive">保活时间：{{ model.keepAlive }}</p>
              </details>
            </td>
            <td>
              {{ model.dimensions ?? '未验证' }}
              <p class="ev-muted">{{ model.verifiedAt ? `最近验证：${verifiedTime(model.verifiedAt)}` : '运行前自动验证' }}</p>
            </td>
            <td>
              <span class="ev-pill">{{
                model.readOnly || model.id === 'current'
                  ? '当前知识库配置 · 只读'
                  : '租户配置'
              }}</span>
              <p>
                <code>{{ model.digest || '模型身份未确认' }}</code>
              </p>
              <span class="ev-muted"
                >{{ model.quantization || '量化未知' }} · Ollama {{ model.ollamaVersion || '版本未知' }}</span
              >
            </td>
            <td>
              <div v-if="!model.readOnly && model.id !== 'current'" class="ev-actions">
                <button
                  class="ev-button"
                  :data-testid="`edit-embedding-${model.id}`"
                  :disabled="busy"
                  @click="edit(model)"
                >
                  编辑</button
                ><button class="ev-button ev-danger" :disabled="busy" @click="remove(model)">删除</button>
              </div>
              <span v-else class="ev-muted">由服务端配置管理</span>
            </td>
          </tr>
          <tr v-if="!loading && !models.length">
            <td colspan="4" class="ev-empty">
              暂无向量模型。添加一个可访问的 Ollama embedding 模型后开始评测。
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
