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

    <section v-if="knowledgeList.length" class="knowledge-list" aria-label="知识库列表">
      <div class="knowledge-list__header" aria-hidden="true">
        <span>知识库</span>
        <span>文档数</span>
        <span>已关联智能体</span>
        <span>操作</span>
      </div>
      <article
        v-for="kb in knowledgeList"
        :key="kb.id"
        class="knowledge-row"
        role="link"
        tabindex="0"
        :aria-label="`打开知识库 ${kb.name}`"
        @click="goToDetail(kb.id)"
        @keyup.enter="goToDetail(kb.id)"
      >
        <span class="knowledge-row__badge" aria-hidden="true">KB</span>
        <div class="knowledge-row__content">
          <h2 class="knowledge-row__title">{{ kb.name }}</h2>
          <p class="knowledge-row__description">{{ kb.desc || '暂未添加描述' }}</p>
        </div>
        <div class="knowledge-row__meta" aria-label="知识库统计">
          <span>{{ kb.docCount || 0 }} 篇文档</span>
          <span>关联 {{ kb.agentCount || 0 }} 个智能体</span>
        </div>
        <div class="knowledge-row__actions">
          <el-button class="knowledge-row__enter" text type="primary" @click.stop="goToDetail(kb.id)">进入知识库</el-button>
          <el-button :aria-label="`删除知识库 ${kb.name}`" class="knowledge-row__delete" type="danger" circle @click.stop="handleDelete(kb.id)"><el-icon class="knowledge-row__delete-icon"><Delete /></el-icon></el-button>
        </div>
      </article>
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

.knowledge-list { width: 100%; }
.knowledge-list__header { display: grid; grid-template-columns: minmax(0, 1fr) 92px 132px 120px; align-items: center; min-height: 36px; padding: 0 18px; color: var(--sea-muted); font-size: 12px; }
.knowledge-list__header > span:not(:first-child) { text-align: right; }
.knowledge-row { display: grid; grid-template-columns: 42px minmax(0, 1fr) 224px auto; align-items: center; column-gap: 16px; min-height: 84px; margin-bottom: 10px; padding: 14px 18px; cursor: pointer; border: 1px solid color-mix(in srgb, var(--sea-mist) 72%, var(--sea-muted)); border-radius: 7px; background: var(--sea-paper); transition: background 180ms ease, border-color 180ms ease; }
.knowledge-row:hover, .knowledge-row:focus-visible { border-color: color-mix(in srgb, var(--sea-signal) 48%, var(--sea-muted)); background: color-mix(in srgb, var(--sea-signal) 4%, var(--sea-paper)); }
.knowledge-row__badge { display: grid; place-items: center; width: 32px; height: 32px; border: 1px solid color-mix(in srgb, var(--sea-signal) 28%, var(--sea-paper)); border-radius: 6px; background: color-mix(in srgb, var(--sea-signal) 7%, var(--sea-paper)); color: var(--sea-signal); font-family: 'JetBrains Mono', monospace; font-size: 10px; font-weight: 700; letter-spacing: .04em; }
.knowledge-row__content { min-width: 0; }
.knowledge-row__title { margin: 0 0 3px; overflow: hidden; color: var(--sea-deep); font-size: 15px; line-height: 1.4; text-overflow: ellipsis; white-space: nowrap; }
.knowledge-row__description { margin: 0; overflow: hidden; color: var(--sea-muted); font-size: 13px; line-height: 1.5; text-overflow: ellipsis; white-space: nowrap; }
.knowledge-row__meta { display: flex; justify-content: flex-end; gap: 18px; color: var(--sea-muted); font-size: 12px; white-space: nowrap; }
.knowledge-row__actions { display: flex; align-items: center; gap: 8px; }
.knowledge-row__enter { min-height: 32px; padding-inline: 9px; font-size: 13px; font-weight: 700; }
.knowledge-row__delete { flex: 0 0 auto; border-color: var(--sea-danger); background: var(--sea-danger); color: var(--sea-paper); }
.knowledge-row__delete:hover, .knowledge-row__delete:focus-visible { border-color: var(--el-color-danger-dark-2); background: var(--el-color-danger-dark-2); color: var(--sea-paper); }
.knowledge-row__delete-icon { font-size: 16px; }

.knowledge-page__empty { display: grid; place-items: center; min-height: 260px; border: 1px dashed var(--el-border-color); border-radius: 12px; background: color-mix(in srgb, var(--sea-paper) 80%, var(--sea-mist)); }
.knowledge-page__empty h2 { margin: 0; color: var(--sea-deep); font-family: 'Noto Serif SC', serif; font-size: 21px; }
.knowledge-page__empty p { margin: 8px 0 16px; }

@media (max-width: 640px) {
  .knowledge-page { padding-top: 8px; }
  .knowledge-page__header { align-items: flex-start; flex-direction: column; }
  .knowledge-page__header > .el-button { width: 100%; }
  .knowledge-list__header { display: none; }
  .knowledge-row { grid-template-columns: 38px minmax(0, 1fr) auto; column-gap: 12px; row-gap: 8px; margin-bottom: 8px; padding: 14px 12px; }
  .knowledge-row__meta { grid-column: 2 / -1; justify-content: flex-start; gap: 12px; }
  .knowledge-row__actions { grid-column: 3; grid-row: 1; gap: 0; }
  .knowledge-row__enter { display: none; }
}
</style>
