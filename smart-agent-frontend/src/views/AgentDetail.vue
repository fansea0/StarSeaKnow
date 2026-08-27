<template>
  <div data-testid="agent-workbench" class="detail-workbench detail-workbench--viewport">
    <!-- 左侧设置区 -->
    <section class="settings-pane">
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
    <section data-testid="debug-preview" class="preview-pane preview-pane--adaptive">
      <div class="chat-header-row">
        <div class="chat-header">调试预览</div>
        <el-button data-testid="save-agent" class="save-agent-btn" type="primary" icon="el-icon-check" @click="saveAgent">保存</el-button>
      </div>
      <div class="chat-history" ref="chatHistoryRef">
        <div v-for="(msg, idx) in chatHistory" :key="idx" :class="['chat-msg', msg.role]">
          <div v-if="msg.role==='assistant'" class="msg-content">
            <img class="avatar-img" src="../assets/avatar.jpg" alt="avatar" />
            <div class="msg-bubble">
              <template v-if="idx === 0">
                <div v-if="prologueGreeting" class="prologue-greeting" v-html="renderMarkdown(prologueGreeting)"></div>
                <div v-if="prologueQuestions.length" class="prologue-questions">
                  <button v-for="(q, i) in prologueQuestions" :key="i" type="button" class="question-chip" @click="sendQuestion(q)">
                    <span class="question-chip-text">{{ q }}</span>
                  </button>
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
      <div data-testid="chat-composer" class="chat-input-row">
        <el-input v-model="inputMsg" placeholder="请输入内容..." @keyup.enter="sendMsg" class="chat-input" />
        <el-button data-testid="send-message" type="primary" icon="el-icon-s-promotion" @click="sendMsg">发送</el-button>
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
.detail-workbench {
  --workbench-rule: color-mix(in srgb, var(--sea-mist) 72%, var(--sea-muted));
  margin-top: 24px;
  display: flex;
  border: 1px solid var(--workbench-rule);
  border-radius: 12px;
  background: var(--sea-paper);
  box-shadow: 0 12px 32px color-mix(in srgb, var(--sea-deep) 10%, transparent);
  overflow: hidden;
  width: 100%;
}

.detail-workbench--viewport {
  height: min(760px, calc(100dvh - 132px));
  min-height: 0;
  max-height: calc(100dvh - 132px);
}

.settings-pane {
  flex: 0 0 40%;
  background: var(--sea-paper);
  padding: 28px 30px 32px;
  border-right: 1px solid var(--workbench-rule);
  min-width: 340px;
  min-height: 0;
  overflow-y: auto;
}
.agent-form .el-form-item {
  margin-bottom: 20px;
}
.knowledge-header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  font-weight: 600;
  color: var(--sea-deep);
  font-size: 15px;
}
.knowledge-list {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 24px;
}
.kb-card {
  display: flex;
  align-items: center;
  padding: 6px 12px;
  border: 1px solid color-mix(in srgb, var(--sea-signal) 20%, var(--sea-paper));
  border-radius: 8px;
  background: color-mix(in srgb, var(--sea-signal) 6%, var(--sea-paper));
  min-width: 90px;
  max-width: 140px;
}
.kb-card:hover {
  border-color: var(--sea-signal);
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
  color: var(--sea-ink);
  font-weight: 500;
  margin-right: 4px;
  text-align: center;
}
.kb-card .el-button { flex-shrink: 0; }
.preview-pane {
  flex: 0 0 60%;
  display: flex;
  flex-direction: column;
  background: var(--sea-deep);
  padding: 0;
  min-width: 400px;
  min-height: 0;
}
.chat-header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 26px 18px 28px;
  border-bottom: 1px solid color-mix(in srgb, var(--sea-mist) 22%, transparent);
  background: color-mix(in srgb, var(--sea-deep) 88%, var(--sea-ink));
  gap: 16px;
}
.save-agent-btn {
  font-weight: 600;
  border: 0;
  color: var(--sea-deep);
  background: var(--sea-sand);
  box-shadow: none;
}
.chat-history {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 28px;
  background: var(--sea-deep);
}
.chat-msg {
  margin-bottom: 18px;
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
  background: var(--sea-mist);
}
.msg-bubble {
  background: color-mix(in srgb, var(--sea-deep) 78%, var(--sea-signal));
  border-radius: 4px 14px 14px;
  padding: 13px 16px;
  width: 100%;
  max-width: 80%;
  font-size: 16px;
  color: var(--sea-paper);
  word-break: break-all;
  box-sizing: border-box;
}
.msg-bubble.user {
  background: var(--sea-signal);
  border-radius: 14px 4px 14px 14px;
  color: var(--sea-deep);
  margin-right: 0;
}
.user {
  flex-direction: row-reverse;
}
.prologue-greeting {
  margin-bottom: 18px;
  font-size: 16px;
  color: var(--sea-paper);
  font-weight: 500;
}
.prologue-questions {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 6px;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  box-sizing: border-box;
  word-break: break-word;
}
.question-chip {
  border: 1px solid color-mix(in srgb, var(--sea-signal) 55%, transparent);
  background: transparent;
  color: color-mix(in srgb, var(--sea-paper) 82%, var(--sea-signal));
  border-radius: 7px;
  padding: 11px 14px;
  font: inherit;
  font-size: 14px;
  cursor: pointer;
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
.question-chip:hover,
.question-chip:focus-visible {
  background: color-mix(in srgb, var(--sea-signal) 24%, transparent);
  color: var(--sea-paper);
}
.question-chip-text {
  flex: 1;
  text-align: left;
}
.chat-input-row {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  padding: 18px 28px 24px;
  border-top: 1px solid color-mix(in srgb, var(--sea-mist) 22%, transparent);
  background: color-mix(in srgb, var(--sea-deep) 88%, var(--sea-ink));
}
.chat-input {
  flex: 1;
  margin-right: 8px;
  box-shadow: none;
  max-width: 700px;
}
.chat-header {
  color: var(--sea-paper);
  font-size: 16px;
  font-weight: 700;
}

@media (max-width: 720px) {
  .detail-workbench--viewport {
    height: auto;
    max-height: none;
    flex-direction: column;
  }

  .settings-pane,
  .preview-pane {
    flex-basis: auto;
    min-width: 0;
  }

  .settings-pane {
    border-right: 0;
    border-bottom: 1px solid var(--workbench-rule);
    overflow-y: visible;
  }

  .preview-pane { min-height: 0; }

  .chat-history {
    flex: 0 0 auto;
    min-height: 260px;
    overflow-y: visible;
  }
}
</style>
