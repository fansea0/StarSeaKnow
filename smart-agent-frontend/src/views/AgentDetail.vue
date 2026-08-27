<template>
  <div class="agent-detail-root">
    <!-- 左侧设置区 -->
    <section class="agent-settings">
      <el-form :model="agentInfo" label-width="80px" class="agent-form">
        <el-form-item label="名称">
          <el-input v-model="agentInfo.name" maxlength="32" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="agentInfo.description" maxlength="256" />
        </el-form-item>
        <el-form-item label="开场白">
          <el-input v-model="agentInfo.prologue" maxlength="512" type="textarea" rows="6" />
        </el-form-item>
        <el-form-item label="角色描述">
          <el-input v-model="agentInfo.roleDescription" maxlength="512" type="textarea" rows="7" />
        </el-form-item>
      </el-form>
      <div class="knowledge-header-row">
        <span>关联知识库</span>
        <el-button size="small" type="primary" icon="el-icon-plus" @click="showAddKnowledge = true">新增关联</el-button>
      </div>
      <div class="knowledge-list">
        <el-card v-for="kb in knowledgeList" :key="kb.id" class="kb-card" shadow="hover">
          <div class="kb-card-content">
            <el-icon v-if="kb.type==='txt'" class="kb-icon"><i class="el-icon-document"></i></el-icon>
            <el-icon v-else-if="kb.type==='md'" class="kb-icon"><i class="el-icon-document-checked"></i></el-icon>
            <el-icon v-else class="kb-icon"><i class="el-icon-folder"></i></el-icon>
            <span class="kb-name">{{ kb.name }}</span>
            <el-button type="danger" size="small" circle @click="removeKnowledge(kb.id)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </div>
        </el-card>
      </div>
      <!-- 新增知识库关联弹窗 -->
      <el-dialog v-model="showAddKnowledge" title="新增知识库关联" width="400px">
        <el-select v-model="selectedKnowledgeIds" multiple placeholder="请选择知识库" style="width: 100%">
          <el-option v-for="item in allKnowledgeList" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <template #footer>
          <el-button @click="showAddKnowledge = false">取消</el-button>
          <el-button type="primary" @click="addKnowledge">关联</el-button>
        </template>
      </el-dialog>
    </section>
    <!-- 右侧调试预览区 -->
    <section class="agent-chat">
      <div class="chat-header-row">
        <div class="chat-header">调试预览</div>
        <el-button class="save-agent-btn" type="primary" icon="el-icon-check" @click="saveAgent">保存</el-button>
      </div>
      <div class="chat-history" ref="chatHistoryRef">
        <div v-for="(msg, idx) in chatHistory" :key="idx" :class="['chat-msg', msg.role]">
          <div v-if="msg.role==='assistant'" class="msg-content">
            <img class="avatar-img" src="../assets/avatar.jpg" alt="avatar" />
            <div class="msg-bubble">
              <template v-if="idx === 0">
                <div v-if="prologueGreeting" class="prologue-greeting" v-html="renderMarkdown(prologueGreeting)"></div>
                <div v-if="prologueQuestions.length" class="prologue-questions">
                  <div v-for="(q, i) in prologueQuestions" :key="i" class="question-chip" @click="sendQuestion(q)">
                    <span class="question-chip-text">{{ q }}</span>
                  </div>
                </div>
              </template>
              <div v-else v-html="renderMarkdown(msg.content)"></div>
            </div>
          </div>
          <div v-else class="msg-content user">
            <div class="msg-bubble user">{{ msg.content }}</div>
          </div>
        </div>
        <div v-if="streamingMsg" class="chat-msg assistant">
          <img class="avatar-img" src="../assets/avatar.jpg" alt="avatar" />
          <div class="msg-bubble"><div v-html="renderMarkdown(streamingMsg)"></div></div>
        </div>
      </div>
      <div class="chat-input-row">
        <el-input v-model="inputMsg" placeholder="请输入内容..." @keyup.enter="sendMsg" class="chat-input" />
        <el-button type="primary" icon="el-icon-s-promotion" @click="sendMsg">发送</el-button>
      </div>
    </section>
  </div>
</template>

<script>
import axios from 'axios'
import { apiUrl } from '../api/http'
import { marked } from 'marked'
import { Delete } from '@element-plus/icons-vue'
export default {
  name: 'AgentDetail',
  components: { Delete },
  data() {
    return {
      agentId: null,
      agentInfo: {
        name: '', description: '', prologue: '', roleDescription: ''
      },
      knowledgeList: [],
      allKnowledgeList: [],
      showAddKnowledge: false,
      selectedKnowledgeIds: [],
      chatId: '',
      chatHistory: [],
      inputMsg: '',
      streamingMsg: '',
      prologueQuestions: [],
      prologueGreeting: ''
    }
  },
  mounted() {
    this.agentId = parseInt(this.$route.params.id)
    this.fetchAgentInfo()
    this.fetchKnowledgeList()
    this.fetchAllKnowledge()
  },
  methods: {
    async fetchAgentInfo() {
      const res = await axios.get(apiUrl(`/agent/${this.agentId}`))
      if (res.data && res.data.code === 200 && res.data.data) {
        this.agentInfo = res.data.data
        this.parsePrologueQuestions()
        this.initChat()
      }
    },
    async fetchKnowledgeList() {
      const res = await axios.get(apiUrl('/agent/knowledge/list'), { params: { agentId: this.agentId } })
      if (res.data && res.data.code === 200) {
        this.knowledgeList = Array.isArray(res.data.data) ? res.data.data : [res.data.data]
      }
    },
    async fetchAllKnowledge() {
      const res = await axios.get(apiUrl('/knowledge/list/vo'))
      if (res.data && res.data.code === 200) {
        this.allKnowledgeList = res.data.data || []
      }
    },
    async addKnowledge() {
      if (!this.selectedKnowledgeIds || this.selectedKnowledgeIds.length === 0) return
      for (const kid of this.selectedKnowledgeIds) {
        await axios.get(apiUrl('/agent/agentToKnowledge'), { params: { agentId: this.agentId, knowledgeId: kid } })
      }
      this.$message.success('关联成功')
      this.showAddKnowledge = false
      this.selectedKnowledgeIds = []
      this.fetchKnowledgeList()
    },
    async removeKnowledge(kbId) {
      // 调用后端解绑接口
      try {
        const res = await axios.delete(apiUrl(`/agent/delete/knowledge/${kbId}`), {
          params: { agentId: this.agentId }
        })
        if (res.data && res.data.code === 200) {
          this.$message.success('已移除关联')
          this.fetchKnowledgeList()
        } else {
          this.$message.error(res.data.msg || '移除失败')
        }
      } catch (e) {
        this.$message.error('移除失败')
      }
    },
    parsePrologueQuestions() {
      // 解析开场白中的markdown列表为可选问题，并分离问候语
      this.prologueQuestions = []
      this.prologueGreeting = ''
      if (!this.agentInfo.prologue) return
      // 分离问候语和问题列表
      const lines = this.agentInfo.prologue.split(/\r?\n/)
      let greetingLines = []
      let questionLines = []
      let inQuestion = false
      for (const line of lines) {
        if (/^\s*[-*] /.test(line)) {
          inQuestion = true
          questionLines.push(line.replace(/^\s*[-*] /, '').trim())
        } else if (!inQuestion && line.trim() !== '') {
          greetingLines.push(line)
        }
      }
      this.prologueGreeting = greetingLines.join(' ')
      this.prologueQuestions = questionLines
    },
    renderMarkdown(text) {
      return marked.parse(text || '')
    },
    async initChat() {
      this.chatId = Math.random().toString(36).slice(2) + Date.now()
      this.chatHistory = []
      // 第一条消息为开场白
      if (this.agentInfo.prologue) {
        this.chatHistory.push({ role: 'assistant', content: this.agentInfo.prologue })
      }
    },
    async sendMsg() {
      if (!this.inputMsg) return
      const msg = this.inputMsg
      this.chatHistory.push({ role: 'user', content: msg })
      this.inputMsg = ''
      this.streamingMsg = ''
      const url = apiUrl(`/ai/agent/chat?chatId=${this.chatId}&agentId=${this.agentId}`)
      const controller = new AbortController()
      const response = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ prompt: msg }),
        signal: controller.signal
      })
      if (!response.body) {
        this.$message.error('无流式响应')
        return
      }
      const reader = response.body.getReader()
      let fullMsg = ''
      const decoder = new TextDecoder('utf-8')
      while (true) {
        const { value, done } = await reader.read()
        if (done) break
        let chunk = decoder.decode(value, { stream: true })
        chunk = chunk.replace(/\r?\n/g, '').trim()
        if (!chunk) continue
        if (chunk === '[DONE]') break
        fullMsg += chunk
        this.streamingMsg = fullMsg
      }
      this.chatHistory.push({ role: 'assistant', content: fullMsg })
      this.streamingMsg = ''
    },
    sendQuestion(q) {
      this.inputMsg = q
      this.sendMsg()
    },
    async saveAgent() {
      try {
        const res = await axios.put(apiUrl(`/agent/update/${this.agentId}`), this.agentInfo)
        if (res.data && res.data.code === 200) {
          this.$message.success('保存成功')
        } else {
          this.$message.error(res.data.msg || '保存失败')
        }
      } catch (e) {
        this.$message.error('保存失败')
      }
    }
  }
}
</script>

<style scoped>
.agent-detail-root {
  margin-top: 40px;
  display: flex;
  height: 640px;
  background: linear-gradient(120deg, #f5faff 0%, #eaf6ff 100%);
  border-radius: 18px;
  box-shadow: 0 4px 24px rgba(64,158,255,0.10);
  overflow: hidden;
  width: 100%;
}
.agent-settings {
  flex: 0 0 40%;
  background: #fff;
  padding: 20px 36px 36px 36px;
  border-right: 1.5px solid #e0e6ed;
  min-width: 340px;
  box-shadow: 2px 0 8px rgba(64,158,255,0.04);
  overflow-y: auto;
}
.agent-form .el-form-item {
  margin-bottom: 22px;
}
.knowledge-header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  font-weight: 600;
  font-size: 16px;
}
.knowledge-list {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin-bottom: 28px;
}
.kb-card {
  display: flex;
  align-items: center;
  padding: 6px 12px;
  border-radius: 10px;
  background: #f0f7ff;
  min-width: 90px;
  max-width: 140px;
  box-shadow: 0 1px 4px rgba(64,158,255,0.08);
  transition: box-shadow 0.2s;
}
.kb-card:hover {
  box-shadow: 0 6px 18px rgba(64,158,255,0.18);
}
.kb-card-content {
  display: flex;
  align-items: center;
  width: 100%;
  justify-content: center;
}
.kb-icon {
  margin-right: 6px;
  font-size: 16px;
}
.kb-name {
  flex: unset;
  font-size: 12px;
  color: #409eff;
  font-weight: 500;
  margin-right: 4px;
  text-align: center;
}
.kb-card .el-button {
  transition: background 0.2s;
  flex-shrink: 0;
}
.kb-card .el-button:hover {
  background: #ffeded;
  color: #f56c6c;
}
.agent-chat {
  flex: 0 0 60%;
  display: flex;
  flex-direction: column;
  background: #fafdff;
  padding: 0;
  min-width: 400px;
}
.chat-header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 50px 18px 36px;
  border-bottom: 1.5px solid #e0e6ed;
  background: #fafdff;
  gap: 18px;
}
.save-agent-btn {
  font-weight: 600;
  font-size: 15px;
  border-radius: 20px;
  background: linear-gradient(90deg, #67c23a 0%, #409eff 100%);
  color: #fff;
  box-shadow: 0 2px 8px rgba(64,158,255,0.10);
  border: none;
  padding: 0 16px;
  height: 32px;
  transition: background 0.2s, color 0.2s;
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  text-align: center !important;
  width: auto;
  margin-right: 30px;
  margin-top: 5px;
}
.save-agent-btn .el-button__text, 
.save-agent-btn span {
  width: 100%;
  text-align: center !important;
  flex: 1;
  display: block;
}
.save-agent-btn:hover {
  background: linear-gradient(90deg, #409eff 0%, #67c23a 100%);
  color: #fff;
  opacity: 0.92;
}
.chat-history {
  flex: 1;
  overflow-y: auto;
  padding: 28px 36px 18px 36px;
  background: #fafdff;
}
.chat-msg {
  margin-bottom: 22px;
  display: flex;
  align-items: flex-start;
}
.msg-content {
  display: flex;
  align-items: flex-start;
}
.avatar-img {
  width: 38px;
  height: 38px;
  border-radius: 50%;
  object-fit: cover;
  margin-right: 14px;
  background: #eaf6ff;
  box-shadow: 0 2px 8px rgba(64,158,255,0.10);
}
.msg-bubble {
  background: linear-gradient(120deg, #f5faff 0%, #eaf6ff 100%);
  border-radius: 16px;
  padding: 14px 20px;
  width: 100%;
  max-width: 80%;
  font-size: 16px;
  color: #333;
  word-break: break-all;
  box-shadow: 0 2px 8px rgba(64,158,255,0.06);
  transition: background 0.2s;
  box-sizing: border-box;
}
.msg-bubble.user {
  background: linear-gradient(120deg, #409eff 0%, #67c23a 100%);
  color: #fff;
  margin-right: 50px;
}
.user {
  flex-direction: row-reverse;
}
.prologue-greeting {
  margin-bottom: 18px;
  font-size: 16px;
  color: #333;
  font-weight: 500;
}
.prologue-questions {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 6px;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  box-sizing: border-box;
  word-break: break-word;
}
.question-chip {
  background: #f4f8ff;
  color: #409eff;
  border-radius: 12px;
  padding: 14px 18px;
  font-size: 16px;
  cursor: pointer;
  transition: background 0.2s, color 0.2s, box-shadow 0.2s;
  border: 1px solid #e3eaf5;
  box-shadow: 0 1px 4px rgba(64,158,255,0.06);
  text-align: left;
  font-weight: 500;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  box-sizing: border-box;
  display: flex;
  align-items: center;
  margin-left: 0;
  word-break: break-word;
}
.question-chip:hover {
  background: #409eff;
  color: #fff;
  box-shadow: 0 4px 16px rgba(64,158,255,0.13);
}
.question-chip-text {
  flex: 1;
  text-align: left;
}
.chat-input-row {
  display: flex;
  align-items: center;
  padding: 18px 36px 28px 36px;
  border-top: 1.5px solid #e0e6ed;
  background: #fafdff;
}
.chat-input {
  flex: 1;
  margin-right: 8px;
  border-radius: 8px;
  box-shadow: 0 1px 4px rgba(64,158,255,0.06);
  max-width: 700px;
}
.el-button {
  border-radius: 8px;
  font-weight: 500;
}
.chat-header {
  font-size: 18px;
  font-weight: 700;
  color: #409eff;
}
</style>
