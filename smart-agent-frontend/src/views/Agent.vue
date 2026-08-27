<template>
  <main class="entity-page">
    <header class="entity-page__header">
      <div>
        <span class="entity-page__eyebrow">智能协作</span>
        <h1>智能体</h1>
        <p>配置面向团队的专属助手，快速连接知识与任务。</p>
      </div>
      <el-button data-testid="create-agent" type="primary" @click="showCreate = true">创建智能体</el-button>
    </header>

    <section v-if="agentList.length" class="entity-grid" aria-label="智能体列表">
      <el-card
        v-for="agent in agentList"
        :key="agent.id"
        class="entity-card"
        shadow="hover"
        @click="goToDetail(agent.id)"
      >
        <div class="entity-card__content">
          <span class="entity-card__kind">智能体</span>
          <h2 class="entity-card__title">{{ agent.name }}</h2>
          <p class="entity-card__description">{{ agent.description || '暂未添加描述' }}</p>
        </div>
        <div class="entity-card__footer">
          <span class="entity-card__hint">查看配置与对话能力</span>
          <el-button :aria-label="`删除智能体 ${agent.name}`" class="entity-card__delete" type="danger" circle @click.stop="handleDelete(agent.id)"><el-icon><Delete /></el-icon></el-button>
        </div>
      </el-card>
    </section>

    <section v-else class="empty-state entity-page__empty" aria-live="polite">
      <h2>还没有智能体</h2>
      <p>创建第一个智能体，为团队提供稳定的专属协作入口。</p>
      <el-button type="primary" @click="showCreate = true">创建智能体</el-button>
    </section>

    <!-- 新建智能体弹窗 -->
    <el-dialog v-model="showCreate" title="新增智能体" width="420px" :close-on-click-modal="false" class="create-dialog">
      <el-form :model="createForm" :rules="rules" ref="createFormRef" label-width="80px" status-icon>
        <el-form-item label="名称" prop="name">
          <el-input v-model="createForm.name" maxlength="32" show-word-limit placeholder="请输入智能体名称" />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="createForm.description" maxlength="256" show-word-limit placeholder="请输入描述" />
        </el-form-item>
        <el-form-item label="开场白" prop="prologue">
          <el-input v-model="createForm.prologue" maxlength="512" show-word-limit placeholder="请输入开场白" />
        </el-form-item>
        <el-form-item label="角色描述" prop="roleDescription">
          <el-input v-model="createForm.roleDescription" maxlength="512" show-word-limit placeholder="请输入角色描述" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreate = false">取消</el-button>
        <el-button type="primary" @click="handleCreate" :loading="loading">创建</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<script>
import { http } from '../api/http'
import { Delete } from '@element-plus/icons-vue'
export default {
  name: 'Agent',
  components: { Delete },
  data() {
    return {
      agentList: [],
      showCreate: false,
      createForm: {
        name: '',
        description: '',
        prologue: '',
        roleDescription: ''
      },
      rules: {
        name: [
          { required: true, message: '请输入智能体名称', trigger: 'blur' },
          { min: 2, max: 32, message: '2-32个字符', trigger: 'blur' }
        ],
        description: [
          { required: true, message: '请输入描述', trigger: 'blur' },
          { max: 100, message: '最多100个字符', trigger: 'blur' }
        ],
        prologue: [
          { required: true, message: '请输入开场白', trigger: 'blur' },
          { max: 100, message: '最多100个字符', trigger: 'blur' }
        ],
        roleDescription: [
          { required: true, message: '请输入角色描述', trigger: 'blur' },
          { max: 100, message: '最多100个字符', trigger: 'blur' }
        ]
      },
      loading: false
    }
  },
  mounted() {
    this.fetchAgentList()
  },
  methods: {
    async fetchAgentList() {
      try {
        const res = await http.get('/agent/list')
        if (res.data && res.data.code === 200) {
          // 兼容data为数组或对象
          if (Array.isArray(res.data.data)) {
            this.agentList = res.data.data
          } else if (res.data.data) {
            this.agentList = [res.data.data]
          } else {
            this.agentList = []
          }
        } else {
          this.$message.error(res.data.msg || '获取智能体失败')
        }
      } catch (e) {
        this.$message.error('网络错误，获取智能体失败')
      }
    },
    goToDetail(id) {
      this.$router.push(`/agent/${id}`)
    },
    async handleCreate() {
      this.$refs.createFormRef.validate(async (valid) => {
        if (valid) {
          this.loading = true
          try {
            const res = await http.post('/agent/add', this.createForm)
            if (res.data && res.data.code === 200) {
              await this.fetchAgentList()
              this.showCreate = false
              this.createForm = { name: '', description: '', prologue: '', roleDescription: '' }
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
      this.$confirm('确定要删除该智能体吗？此操作不可恢复！', '提示', {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning',
      }).then(async () => {
        try {
          const res = await http.delete(`/agent/delete/${id}`)
          if (res.data && res.data.code === 200) {
            this.$message.success('删除成功')
            this.fetchAgentList()
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

.entity-card__hint { color: var(--sea-muted); font-size: 13px; }

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
