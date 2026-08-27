<template>
  <div class="knowledge-list-page">
    <div class="header-row">
      <h3>知识库</h3>
      <el-button type="primary" icon="el-icon-plus" @click="showCreate = true">创建知识库</el-button>
    </div>
    <div class="knowledge-list">
      <el-card
        v-for="kb in knowledgeList"
        :key="kb.id"
        class="knowledge-card"
        shadow="hover"
        @click="goToDetail(kb.id)"
      >
        <div class="card-title-row">
          <span class="kb-title">{{ kb.name }}</span>
        </div>
        <div class="kb-desc">{{ kb.desc }}</div>
        <div class="kb-meta-row">
          <span class="meta-item">📄 文档：{{ kb.docCount }}</span>
          <span class="meta-item">🤖 智能体：{{ kb.agentCount }}</span>
          <el-button class="delete-kb-btn" type="danger" circle size="small" @click.stop="handleDelete(kb.id)"><el-icon><Delete /></el-icon></el-button>
        </div>
      </el-card>
    </div>
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
  </div>
</template>

<script>
import { ref } from 'vue'
import axios from 'axios'
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
        const res = await axios.get('http://localhost:8080/knowledge/list/vo')
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
            const res = await axios.post('http://localhost:8080/knowledge/add', {
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
          const res = await axios.delete(`http://localhost:8080/knowledge/delete/${id}`)
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
.knowledge-list-page {
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
.knowledge-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 24px;
  width: 100%;
  padding: 0 32px;
  box-sizing: border-box;
}
.knowledge-card {
  border-radius: 18px;
  cursor: pointer;
  transition: box-shadow 0.2s, transform 0.2s;
  border: none;
  box-shadow: 0 2px 12px rgba(64,158,255,0.08);
  padding: 20px 24px 18px 24px;
  background: #fff;
}
.knowledge-card:hover {
  box-shadow: 0 6px 24px rgba(64,158,255,0.18);
  transform: translateY(-2px) scale(1.02);
}
.card-title-row {
  display: flex;
  align-items: center;
  margin-bottom: 8px;
}
.kb-title {
  font-size: 20px;
  font-weight: 600;
  color: #409eff;
  margin-right: 8px;
}
.kb-desc {
  color: #666;
  font-size: 15px;
  margin-bottom: 18px;
  min-height: 36px;
}
.kb-meta-row {
  display: flex;
  gap: 18px;
  color: #888;
  font-size: 14px;
}
.meta-item {
  display: flex;
  align-items: center;
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
.delete-kb-btn {
  margin-left: 8px;
  vertical-align: middle;
  background: #fff0f0;
  border: none;
  color: #f56c6c;
  box-shadow: none;
  transition: background 0.2s, color 0.2s;
}
.delete-kb-btn:hover {
  background: #ffeded;
  color: #d9001b;
}
</style> 