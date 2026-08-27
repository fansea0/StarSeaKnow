<template>
  <div class="agent-list-page">
    <div class="header-row">
      <h3>智能体</h3>
      <el-button type="primary" icon="el-icon-plus" @click="showCreate = true">新增智能体</el-button>
    </div>
    <div class="agent-list">
      <el-card
        v-for="agent in agentList"
        :key="agent.id"
        class="agent-card"
        shadow="hover"
        @click="goToDetail(agent.id)"
      >
        <div class="card-title-row">
          <span class="agent-title">{{ agent.name }}</span>
        </div>
        <div class="agent-desc">{{ agent.description }}</div>
        <el-button class="delete-agent-btn" type="danger" circle size="small" @click.stop="handleDelete(agent.id)"><el-icon><Delete /></el-icon></el-button>
      </el-card>
    </div>
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
  </div>
</template>

<script>
import axios from 'axios'
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
        const res = await axios.get('http://localhost:8080/agent/list')
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
            const res = await axios.post('http://localhost:8080/agent/add', this.createForm)
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
          const res = await axios.delete(`http://localhost:8080/agent/delete/${id}`)
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
.agent-list-page {
  width: 100%;
  padding: 32px 0 48px 0;
}
.header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 0px;
}
.header-row h3 {
  margin-left: 32px;
}
.agent-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 24px;
  width: 100%;
  padding: 0 32px;
  box-sizing: border-box;
}
.agent-card {
  border-radius: 18px;
  cursor: pointer;
  transition: box-shadow 0.2s, transform 0.2s;
  border: none;
  box-shadow: 0 2px 12px rgba(64,158,255,0.08);
  padding: 20px 24px 18px 24px;
  background: #fff;
  position: relative;
}
.agent-card:hover {
  box-shadow: 0 6px 24px rgba(64,158,255,0.18);
  transform: translateY(-2px) scale(1.02);
}
.card-title-row {
  display: flex;
  align-items: center;
  margin-bottom: 8px;
}
.agent-title {
  font-size: 20px;
  font-weight: 600;
  color: #409eff;
  margin-right: 8px;
}
.agent-desc {
  color: #666;
  font-size: 15px;
  margin-bottom: 18px;
  min-height: 36px;
}
.create-dialog >>> .el-dialog {
  border-radius: 16px;
}
.create-dialog >>> .el-dialog__header {
  font-size: 20px;
  font-weight: 600;
  color: #409eff;
  border-bottom: 1px solid #f0f0f0;
  padding-bottom: 8px;
}
.create-dialog >>> .el-dialog__body {
  padding-top: 18px;
  padding-bottom: 0;
}
.create-dialog >>> .el-form-item__label {
  font-weight: 500;
}
.delete-agent-btn {
  position: absolute;
  right: 16px;
  bottom: 16px;
  background: #fff0f0;
  border: none;
  color: #f56c6c;
  box-shadow: none;
  transition: background 0.2s, color 0.2s;
  z-index: 2;
}
.delete-agent-btn:hover {
  background: #ffeded;
  color: #d9001b;
}
</style> 