<template>
  <main class="entity-page">
    <header class="entity-page__header">
      <div>
        <span class="entity-page__eyebrow">知识管理</span>
        <h1>知识库</h1>
        <p>沉淀资料，让智能体更可靠地回答问题。</p>
      </div>
      <el-button data-testid="create-knowledge" type="primary" @click="showCreate = true">创建知识库</el-button>
    </header>

    <section v-if="knowledgeList.length" class="entity-grid" aria-label="知识库列表">
      <el-card
        v-for="kb in knowledgeList"
        :key="kb.id"
        class="entity-card"
        shadow="hover"
        @click="goToDetail(kb.id)"
      >
        <div class="entity-card__content">
          <span class="entity-card__kind">知识库</span>
          <h2 class="entity-card__title">{{ kb.name }}</h2>
          <p class="entity-card__description">{{ kb.desc || '暂未添加描述' }}</p>
        </div>
        <div class="entity-card__footer">
          <dl class="entity-card__stats" aria-label="知识库统计">
            <div><dt>文档</dt><dd>{{ kb.docCount || 0 }}</dd></div>
            <div><dt>智能体</dt><dd>{{ kb.agentCount || 0 }}</dd></div>
          </dl>
          <el-button :aria-label="`删除知识库 ${kb.name}`" class="entity-card__delete" type="danger" circle @click.stop="handleDelete(kb.id)"><el-icon><Delete /></el-icon></el-button>
        </div>
      </el-card>
    </section>

    <section v-else class="empty-state entity-page__empty" aria-live="polite">
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
.entity-page {
  width: 100%;
  padding: 12px 0 12px;
}

.entity-page__header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 26px;
}

.entity-page__eyebrow {
  color: var(--sea-signal);
  font-family: 'JetBrains Mono', monospace;
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.08em;
}

.entity-page h1 {
  margin: 4px 0 0;
  color: var(--sea-deep);
  font-family: 'Noto Serif SC', serif;
  font-size: clamp(28px, 3vw, 36px);
  line-height: 1.2;
}

.entity-page__header p {
  margin: 8px 0 0;
  color: var(--sea-muted);
}

.entity-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 270px), 1fr));
  gap: 16px;
  width: 100%;
}

.entity-card {
  min-height: 228px;
  cursor: pointer;
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
  background: var(--sea-paper);
  box-shadow: 0 8px 20px rgb(17 36 59 / 5%);
  transition: border-color 180ms ease, box-shadow 180ms ease;
}

.entity-card:hover {
  border-color: color-mix(in srgb, var(--sea-signal) 48%, var(--el-border-color-light));
  box-shadow: 0 12px 26px rgb(17 36 59 / 9%);
}

.entity-card :deep(.el-card__body) {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 226px;
  padding: 22px;
}

.entity-card__content { min-width: 0; }

.entity-card__kind {
  display: inline-flex;
  padding: 3px 8px;
  border-radius: var(--el-border-radius-round);
  background: color-mix(in srgb, var(--sea-signal) 12%, var(--sea-paper));
  color: var(--sea-signal);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
}

.entity-card__title {
  margin: 12px 0 8px;
  overflow: hidden;
  color: var(--sea-deep);
  font-size: 19px;
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.entity-card__description {
  display: -webkit-box;
  min-height: 44px;
  margin: 0;
  overflow: hidden;
  color: var(--sea-muted);
  font-size: 14px;
  line-height: 1.6;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.entity-card__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  width: 100%;
  margin-top: auto;
  padding-top: 18px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.entity-card__stats {
  display: flex;
  gap: 20px;
  margin: 0;
}

.entity-card__stats div { min-width: 42px; }
.entity-card__stats dt { color: var(--sea-muted); font-size: 12px; }
.entity-card__stats dd { margin: 2px 0 0; color: var(--sea-ink); font-family: 'JetBrains Mono', monospace; font-size: 16px; font-weight: 500; }

.entity-card__delete {
  flex: 0 0 auto;
  color: var(--sea-danger);
}

.entity-page__empty {
  display: grid;
  place-items: center;
  min-height: 260px;
  border: 1px dashed var(--el-border-color);
  border-radius: 12px;
  background: color-mix(in srgb, var(--sea-paper) 80%, var(--sea-mist));
}

.entity-page__empty h2 { margin: 0; color: var(--sea-deep); font-family: 'Noto Serif SC', serif; font-size: 21px; }
.entity-page__empty p { margin: 8px 0 16px; }

@media (max-width: 640px) {
  .entity-page { padding-top: 8px; }
  .entity-page__header { align-items: flex-start; flex-direction: column; }
  .entity-page__header > .el-button { width: 100%; }
  .entity-grid { grid-template-columns: 1fr; }
}
</style>
