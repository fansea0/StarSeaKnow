<template>
  <main class="knowledge-page">
    <header class="knowledge-page__header">
      <div>
        <span class="knowledge-page__eyebrow">整理资料</span>
        <h1>知识库</h1>
        <p>将团队文件整理为可检索的资料索引，持续为智能体提供依据。</p>
      </div>
      <el-button data-testid="create-knowledge" type="primary" @click="showCreate = true">创建知识库</el-button>
    </header>

    <section v-if="knowledgeList.length" class="knowledge-management" aria-label="知识库管理">
      <div class="knowledge-toolbar">
        <el-input
          v-model.trim="searchQuery"
          data-testid="knowledge-search"
          clearable
          placeholder="搜索知识库名称或描述"
          aria-label="搜索知识库名称或描述"
        />
      </div>

      <section v-if="filteredKnowledgeList.length" class="knowledge-list" aria-label="知识库列表">
        <div class="knowledge-list__header" aria-hidden="true">
          <span>知识库</span>
          <span>描述</span>
          <span>文档数</span>
          <span>已关联智能体</span>
          <span>操作</span>
        </div>
        <article
          v-for="kb in filteredKnowledgeList"
          :key="kb.id"
          class="knowledge-row"
          role="link"
          tabindex="0"
          :aria-label="`打开知识库 ${kb.name}`"
          @click="goToDetail(kb.id)"
          @keyup.enter="goToDetail(kb.id)"
        >
          <div class="knowledge-row__identity">
            <span class="knowledge-row__badge" aria-hidden="true">KB</span>
            <h2 class="knowledge-row__title">{{ kb.name }}</h2>
          </div>
          <p class="knowledge-row__description">{{ kb.desc || '暂未添加描述' }}</p>
          <span class="knowledge-row__document-count">{{ kb.docCount || 0 }}</span>
          <span class="knowledge-row__agent-count">{{ kb.agentCount || 0 }}</span>
          <div class="knowledge-row__actions">
            <el-button class="knowledge-row__enter" text type="primary" @click.stop="goToDetail(kb.id)">进入知识库</el-button>
            <el-button :aria-label="`删除知识库 ${kb.name}`" class="knowledge-row__delete" type="danger" circle @click.stop="handleDelete(kb.id)"><el-icon class="knowledge-row__delete-icon"><Delete /></el-icon></el-button>
          </div>
        </article>
      </section>

      <section v-else data-testid="knowledge-search-empty" class="knowledge-search-empty" aria-live="polite">
        <h2>没有找到匹配的知识库</h2>
        <p>换一个关键词，或创建一个新的知识库。</p>
      </section>
    </section>

    <section v-else class="empty-state knowledge-page__empty" aria-live="polite">
      <h2>还没有知识库</h2>
      <p>从第一个知识库开始，集中管理智能体需要的资料。</p>
      <el-button type="primary" @click="showCreate = true">创建知识库</el-button>
    </section>

    <!-- 新建知识库弹窗 -->
    <el-dialog v-model="showCreate" title="创建知识库" width="420px" :close-on-click-modal="false" class="create-dialog">
      <el-form :model="createForm" :rules="rules" ref="createFormRef" label-width="72px" status-icon>
        <el-form-item label="名称" prop="name">
          <el-input v-model="createForm.name" maxlength="32" show-word-limit placeholder="请输入知识库名称" />
        </el-form-item>
        <el-form-item label="描述" prop="desc">
          <el-input v-model="createForm.desc" type="textarea" :rows="3" maxlength="100" show-word-limit placeholder="请输入描述" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreate = false">取消</el-button>
        <el-button type="primary" @click="handleCreate">创建</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<script>
import { ref } from 'vue'
import { http } from '../api/http'
import { Delete } from '@element-plus/icons-vue'
export default {
  name: 'Knowledge',
  components: { Delete },
  data() {
    return {
      knowledgeList: [],
      searchQuery: '',
      showCreate: false,
      createForm: {
        name: '',
        desc: ''
      },
      rules: {
        name: [
          { required: true, message: '请输入知识库名称', trigger: 'blur' },
          { min: 2, max: 32, message: '2-32个字符', trigger: 'blur' }
        ],
        desc: [
          { max: 100, message: '最多100个字符', trigger: 'blur' }
        ]
      },
      loading: false
    }
  },
  mounted() {
    this.fetchKnowledgeList()
  },
  computed: {
    filteredKnowledgeList() {
      const query = this.searchQuery.trim().toLowerCase()
      if (!query) return this.knowledgeList
      return this.knowledgeList.filter(item => {
        const name = String(item.name || '').toLowerCase()
        const description = String(item.desc || '').toLowerCase()
        return name.includes(query) || description.includes(query)
      })
    }
  },
  methods: {
    async fetchKnowledgeList() {
      try {
        const res = await http.get('/knowledge/list/vo')
        if (res.data && res.data.code === 200) {
          this.knowledgeList = (res.data.data || []).map(item => ({
            name: item.name,
            desc: item.description,
            docCount: item.fileCount,
            agentCount: item.agentCount,
            id: item.id
          }))
        } else {
          this.$message.error(res.data.msg || '获取知识库失败')
        }
      } catch (e) {
        this.$message.error('网络错误，获取知识库失败')
      }
    },
    goToDetail(id) {
      this.$router.push(`/knowledge/${id}`)
    },
    async handleCreate() {
      this.$refs.createFormRef.validate(async (valid) => {
        if (valid) {
          this.loading = true
          try {
            const res = await http.post('/knowledge/add', {
              name: this.createForm.name,
              description: this.createForm.desc
            })
            if (res.data && res.data.code === 200) {
              // 创建成功后刷新列表
              await this.fetchKnowledgeList()
              this.showCreate = false
              this.createForm = { name: '', desc: '' }
              this.$message.success('创建成功！')
            } else {
              this.$message.error(res.data.msg || '创建失败')
            }
          } catch (e) {
            this.$message.error('网络错误，创建失败')
          } finally {
            this.loading = false
          }
        }
      })
    },
    async handleDelete(id) {
      this.$confirm('确定要删除该知识库吗？此操作不可恢复！', '提示', {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning',
      }).then(async () => {
        try {
          const res = await http.delete(`/knowledge/delete/${id}`)
          if (res.data && res.data.code === 200) {
            this.$message.success('删除成功')
            this.fetchKnowledgeList()
          } else {
            this.$message.error(res.data.msg || '删除失败')
          }
        } catch (e) {
          this.$message.error('网络错误，删除失败')
        }
      }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.knowledge-page { width: 100%; padding: 12px 0; }
.knowledge-page__header { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; margin-bottom: 26px; }
.knowledge-page__eyebrow { color: var(--sea-signal); font-family: 'JetBrains Mono', monospace; font-size: 12px; font-weight: 500; letter-spacing: .08em; }
.knowledge-page h1 { margin: 4px 0 0; color: var(--sea-deep); font-family: 'Noto Serif SC', serif; font-size: clamp(28px, 3vw, 36px); line-height: 1.2; }
.knowledge-page__header p { margin: 8px 0 0; color: var(--sea-muted); }

.knowledge-management { width: 100%; }
.knowledge-toolbar { display: flex; justify-content: flex-end; margin-bottom: 14px; }
.knowledge-toolbar :deep(.el-input) { width: min(100%, 320px); }
.knowledge-toolbar :deep(.el-input__wrapper) { min-height: 38px; box-shadow: 0 0 0 1px color-mix(in srgb, var(--sea-muted) 28%, var(--sea-paper)) inset; }
.knowledge-list { width: 100%; }
.knowledge-list__header { display: grid; grid-template-columns: minmax(180px, 1.1fr) minmax(200px, 1.4fr) 72px 116px 120px; align-items: center; min-height: 36px; padding: 0 18px; color: var(--sea-muted); font-size: 12px; }
.knowledge-list__header > span:not(:first-child) { text-align: right; }
.knowledge-row { display: grid; grid-template-columns: minmax(180px, 1.1fr) minmax(200px, 1.4fr) 72px 116px auto; align-items: center; column-gap: 16px; min-height: 74px; margin-bottom: 10px; padding: 12px 18px; cursor: pointer; border: 1px solid color-mix(in srgb, var(--sea-mist) 72%, var(--sea-muted)); border-radius: 7px; background: var(--sea-paper); transition: background 180ms ease, border-color 180ms ease; }
.knowledge-row:hover, .knowledge-row:focus-visible { border-color: color-mix(in srgb, var(--sea-signal) 48%, var(--sea-muted)); background: color-mix(in srgb, var(--sea-signal) 4%, var(--sea-paper)); }
.knowledge-row__badge { display: grid; place-items: center; width: 32px; height: 32px; border: 1px solid color-mix(in srgb, var(--sea-signal) 28%, var(--sea-paper)); border-radius: 6px; background: color-mix(in srgb, var(--sea-signal) 7%, var(--sea-paper)); color: var(--sea-signal); font-family: 'JetBrains Mono', monospace; font-size: 10px; font-weight: 700; letter-spacing: .04em; }
.knowledge-row__identity { display: flex; align-items: center; min-width: 0; gap: 12px; }
.knowledge-row__title { margin: 0 0 3px; overflow: hidden; color: var(--sea-deep); font-size: 15px; line-height: 1.4; text-overflow: ellipsis; white-space: nowrap; }
.knowledge-row__description { margin: 0; overflow: hidden; color: var(--sea-muted); font-size: 13px; line-height: 1.5; text-overflow: ellipsis; white-space: nowrap; }
.knowledge-row__document-count, .knowledge-row__agent-count { color: var(--sea-muted); font-family: 'JetBrains Mono', monospace; font-size: 13px; text-align: right; }
.knowledge-row__actions { display: flex; align-items: center; gap: 8px; }
.knowledge-row__enter { min-height: 32px; padding-inline: 9px; font-size: 13px; font-weight: 700; }
.knowledge-row__delete { flex: 0 0 auto; border-color: var(--sea-danger); background: var(--sea-danger); color: var(--sea-paper); }
.knowledge-row__delete:hover, .knowledge-row__delete:focus-visible { border-color: var(--el-color-danger-dark-2); background: var(--el-color-danger-dark-2); color: var(--sea-paper); }
.knowledge-row__delete-icon { font-size: 16px; }

.knowledge-search-empty { display: grid; place-items: center; min-height: 190px; border: 1px dashed color-mix(in srgb, var(--sea-mist) 62%, var(--sea-muted)); border-radius: 8px; background: color-mix(in srgb, var(--sea-paper) 88%, var(--sea-mist)); text-align: center; }
.knowledge-search-empty h2 { margin: 0; color: var(--sea-deep); font-size: 16px; }
.knowledge-search-empty p { margin: 7px 0 0; color: var(--sea-muted); font-size: 13px; }

.knowledge-page__empty { display: grid; place-items: center; min-height: 260px; border: 1px dashed var(--el-border-color); border-radius: 12px; background: color-mix(in srgb, var(--sea-paper) 80%, var(--sea-mist)); }
.knowledge-page__empty h2 { margin: 0; color: var(--sea-deep); font-family: 'Noto Serif SC', serif; font-size: 21px; }
.knowledge-page__empty p { margin: 8px 0 16px; }

@media (max-width: 640px) {
  .knowledge-page { padding-top: 8px; }
  .knowledge-page__header { align-items: flex-start; flex-direction: column; }
  .knowledge-page__header > .el-button { width: 100%; }
  .knowledge-toolbar { justify-content: stretch; }
  .knowledge-toolbar :deep(.el-input) { width: 100%; }
  .knowledge-list__header { display: none; }
  .knowledge-row { grid-template-columns: minmax(0, 1fr) auto; column-gap: 12px; row-gap: 8px; margin-bottom: 8px; padding: 14px 12px; }
  .knowledge-row__description { grid-column: 1 / -1; grid-row: 2; }
  .knowledge-row__document-count { grid-column: 1; grid-row: 3; text-align: left; }
  .knowledge-row__document-count::after { content: ' 篇文档'; }
  .knowledge-row__agent-count { grid-column: 1; grid-row: 3; justify-self: end; text-align: right; }
  .knowledge-row__agent-count::before { content: '关联 '; }
  .knowledge-row__agent-count::after { content: ' 个智能体'; }
  .knowledge-row__actions { grid-column: 2; grid-row: 1; gap: 0; }
  .knowledge-row__enter { display: none; }
}
</style>
