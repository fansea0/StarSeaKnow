<template>
  <main class="agent-page">
    <header class="agent-page__header">
      <div>
        <span class="agent-page__eyebrow">配置协作者</span>
        <h1>智能体</h1>
        <p>为团队配置角色清晰、随时可以对话验证的专属协作者。</p>
      </div>
      <el-button data-testid="create-agent" type="primary" @click="showCreate = true">新增智能体</el-button>
    </header>

    <section v-if="agentList.length" class="agent-roster" aria-label="智能体列表">
      <article
        v-for="agent in agentList"
        :key="agent.id"
        class="agent-profile"
        role="link"
        tabindex="0"
        :aria-label="`打开并调试智能体 ${agent.name}`"
        @click="goToDetail(agent.id)"
        @keyup.enter="goToDetail(agent.id)"
      >
        <div class="agent-profile__marker" aria-hidden="true">协</div>
        <div class="agent-profile__content">
          <span class="agent-profile__kind">团队协作者</span>
          <h2 class="agent-profile__title">{{ agent.name }}</h2>
          <p class="agent-profile__mission">{{ agent.description || '暂未添加描述' }}</p>
        </div>
        <div class="agent-profile__actions">
          <el-button class="agent-profile__open" text type="primary" @click.stop="goToDetail(agent.id)">打开并调试</el-button>
          <el-button :aria-label="`删除智能体 ${agent.name}`" class="agent-profile__delete" type="danger" circle @click.stop="handleDelete(agent.id)"><el-icon class="agent-profile__delete-icon"><Delete /></el-icon></el-button>
        </div>
      </article>
    </section>

    <section v-else class="empty-state agent-page__empty" aria-live="polite">
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
        description: ''
      },
      rules: {
        name: [
          { required: true, message: '请输入智能体名称', trigger: 'blur' },
          { min: 2, max: 32, message: '2-32个字符', trigger: 'blur' }
        ],
        description: [
          { required: true, message: '请输入描述', trigger: 'blur' },
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
              this.createForm = { name: '', description: '' }
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
.agent-page {
  width: 100%;
  padding: 12px 0 12px;
}

.agent-page__header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 26px;
}

.agent-page__eyebrow {
  color: var(--sea-signal);
  font-family: 'JetBrains Mono', monospace;
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.08em;
}

.agent-page h1 {
  margin: 4px 0 0;
  color: var(--sea-deep);
  font-family: 'Noto Serif SC', serif;
  font-size: clamp(28px, 3vw, 36px);
  line-height: 1.2;
}

.agent-page__header p {
  margin: 8px 0 0;
  color: var(--sea-muted);
}

.agent-roster {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 380px), 1fr));
  gap: 22px 28px;
  width: 100%;
}

.agent-profile {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: start;
  min-height: 186px;
  padding: 28px 0 25px;
  cursor: pointer;
  border-bottom: 1px solid color-mix(in srgb, var(--sea-mist) 70%, var(--sea-muted));
  border-left: 3px solid color-mix(in srgb, var(--sea-sand) 74%, var(--sea-paper));
  background: var(--sea-paper);
  transition: border-color 180ms ease, background 180ms ease;
}

.agent-profile:hover,
.agent-profile:focus-visible {
  border-left-color: var(--sea-signal);
  background: color-mix(in srgb, var(--sea-signal) 4%, var(--sea-paper));
}

.agent-profile__marker {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  margin: 1px 16px 0 20px;
  border: 1px solid color-mix(in srgb, var(--sea-sand) 72%, var(--sea-paper));
  border-radius: 50%;
  background: color-mix(in srgb, var(--sea-sand) 19%, var(--sea-paper));
  color: var(--sea-deep);
  font-family: 'Noto Serif SC', serif;
  font-size: 15px;
  font-weight: 700;
}

.agent-profile__content { min-width: 0; }

.agent-profile__kind {
  display: inline-flex;
  color: var(--sea-muted);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.08em;
}

.agent-profile__title {
  margin: 8px 0 9px;
  overflow: hidden;
  color: var(--sea-deep);
  font-family: 'Noto Serif SC', serif;
  font-size: 22px;
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-profile__mission {
  display: -webkit-box;
  min-height: 46px;
  margin: 0;
  overflow: hidden;
  color: var(--sea-muted);
  font-size: 14px;
  line-height: 1.6;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.agent-profile__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: auto 20px 0 18px;
}

.agent-profile__open {
  padding: 7px 9px;
  font-size: 13px;
  font-weight: 700;
}
.agent-profile__open::after { content: '↗'; margin-left: 5px; }

.agent-profile__delete {
  flex: 0 0 auto;
  border-color: var(--sea-danger);
  background: var(--sea-danger);
  color: var(--sea-paper);
}

.agent-profile__delete:hover,
.agent-profile__delete:focus-visible {
  border-color: var(--el-color-danger-dark-2);
  background: var(--el-color-danger-dark-2);
  color: var(--sea-paper);
}

.agent-profile__delete-icon {
  font-size: 16px;
}

.agent-page__empty {
  display: grid;
  place-items: center;
  min-height: 260px;
  border: 1px dashed var(--el-border-color);
  border-radius: 12px;
  background: color-mix(in srgb, var(--sea-paper) 80%, var(--sea-mist));
}

.agent-page__empty h2 { margin: 0; color: var(--sea-deep); font-family: 'Noto Serif SC', serif; font-size: 21px; }
.agent-page__empty p { margin: 8px 0 16px; }

@media (max-width: 640px) {
  .agent-page { padding-top: 8px; }
  .agent-page__header { align-items: flex-start; flex-direction: column; }
  .agent-page__header > .el-button { width: 100%; }
  .agent-roster { grid-template-columns: 1fr; gap: 12px; }
  .agent-profile { min-height: 160px; padding: 22px 0; }
  .agent-profile__marker { margin-left: 14px; margin-right: 12px; }
  .agent-profile__actions { margin-right: 12px; margin-left: 10px; }
}
</style>
