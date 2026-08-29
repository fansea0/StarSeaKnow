<template>
  <div data-testid="agent-workbench" class="detail-workbench detail-workbench--viewport">
    <!-- 左侧设置区 -->
    <section data-testid="agent-editor-canvas" class="settings-pane settings-pane--flush">
      <el-form :model="agentInfo" label-width="96px" class="agent-form">
        <div class="form-section-label">
          <span>基础资料</span>
          <small>定义团队识别的智能体信息</small>
        </div>
        <el-form-item label="名称">
          <el-input v-model="agentInfo.name" maxlength="32" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="agentInfo.description" maxlength="256" />
        </el-form-item>
        <div class="form-section-label form-section-label--conversation">
          <span>对话设定</span>
          <small>设定首次出现时的语气与职责</small>
        </div>
        <el-form-item label="开场白">
          <el-input v-model="agentInfo.prologue" maxlength="512" type="textarea" rows="6" />
        </el-form-item>
        <el-form-item label="角色描述">
          <el-input v-model="agentInfo.roleDescription" maxlength="512" type="textarea" rows="7" />
        </el-form-item>
        <div class="model-config-toggle">
          <div>
            <span>模型配置</span>
            <small>{{ agentInfo.modelApiKeyConfigured ? '已配置 API Key' : '尚未配置模型' }}</small>
          </div>
          <el-button data-testid="toggle-model-config" text type="primary" @click="showModelConfig = !showModelConfig">
            {{ showModelConfig ? '收起' : '配置模型' }}
          </el-button>
        </div>
        <section v-if="showModelConfig" class="model-config-panel">
          <p>此配置仅用于当前智能体；保存的 API Key 不会回显。</p>
          <el-form-item label="接口地址">
            <el-input data-testid="model-url" v-model.trim="agentInfo.modelUrl" maxlength="512" placeholder="例如：https://api.openai.com/v1" />
          </el-form-item>
          <el-form-item label="API Key">
            <el-input data-testid="model-api-key" v-model="agentInfo.modelApiKey" type="password" show-password autocomplete="new-password" :placeholder="agentInfo.modelApiKeyConfigured ? '已配置；留空则保持不变' : '请输入 API Key'" />
          </el-form-item>
          <el-form-item label="模型 ID">
            <el-input data-testid="model-id" v-model.trim="agentInfo.modelId" maxlength="128" placeholder="例如：gpt-4o-mini" />
          </el-form-item>
        </section>
      </el-form>
      <div class="knowledge-header-row">
        <div>
          <span>知识来源</span>
          <small>为本智能体提供可检索的资料</small>
        </div>
        <el-button size="small" type="primary" icon="el-icon-plus" @click="showAddKnowledge = true">新增关联</el-button>
      </div>
      <div data-testid="knowledge-links" class="knowledge-list">
        <div v-for="kb in knowledgeList" :key="kb.id" class="knowledge-link">
          <div class="knowledge-link-content">
            <el-icon v-if="kb.type==='txt'" class="kb-icon"><i class="el-icon-document"></i></el-icon>
            <el-icon v-else-if="kb.type==='md'" class="kb-icon"><i class="el-icon-document-checked"></i></el-icon>
            <el-icon v-else class="kb-icon"><i class="el-icon-folder"></i></el-icon>
            <span class="kb-name">{{ kb.name }}</span>
            <el-button type="danger" size="small" circle :aria-label="`移除知识库：${kb.name}`" @click="removeKnowledge(kb.id)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </div>
        </div>
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
      <div class="preview-instrument">
        <div class="chat-header-row">
          <div class="chat-heading">
            <div class="chat-header">调试预览</div>
            <span>实时会话</span>
          </div>
          <el-button data-testid="save-agent" class="save-agent-btn" type="primary" icon="el-icon-check" @click="saveAgent">保存</el-button>
        </div>
        <div class="chat-history" ref="chatHistoryRef">
          <div v-for="(msg, idx) in chatHistory" :key="idx" :class="['chat-msg', msg.role]">
            <div v-if="msg.role==='assistant'" class="msg-content">
              <img class="avatar-img" src="../assets/avatar.jpg" alt="助手头像" />
              <div class="msg-bubble">
                <template v-if="msg.isPrologue">
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
        </div>
        <div data-testid="chat-composer" class="chat-input-row">
          <el-input v-model="inputMsg" :disabled="isStreaming" placeholder="输入消息，测试此智能体…" @keyup.enter="sendMsg" class="chat-input" />
          <el-button data-testid="send-message" :disabled="isStreaming" type="primary" icon="el-icon-s-promotion" @click="sendMsg">发送</el-button>
        </div>
      </div>
    </section>
  </div>
</template>

<script>
import axios from 'axios'
import { apiUrl } from '../api/http'
import { authenticatedFetch } from '../api/authenticatedFetch'
import { marked } from 'marked'
import { Delete } from '@element-plus/icons-vue'
export default {
  name: 'AgentDetail',
  components: { Delete },
  data() {
    return {
      agentId: null,
      agentInfo: {
        name: '', description: '', prologue: '', roleDescription: '',
        modelUrl: '', modelApiKey: '', modelId: '', modelApiKeyConfigured: false
      },
      knowledgeList: [],
      allKnowledgeList: [],
      showAddKnowledge: false,
      showModelConfig: false,
      selectedKnowledgeIds: [],
      chatId: '',
      chatHistory: [],
      inputMsg: '',
      isStreaming: false,
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
        this.agentInfo = { ...this.agentInfo, ...res.data.data, modelApiKey: '' }
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
        this.chatHistory.push({ role: 'assistant', content: this.agentInfo.prologue, isPrologue: true })
      }
    },
    async sendMsg() {
      if (!this.inputMsg || this.isStreaming) return
      const msg = this.inputMsg
      this.chatHistory.push({ role: 'user', content: msg })
      this.inputMsg = ''
      const assistantMessage = { role: 'assistant', content: '' }
      const assistantMessageIndex = this.chatHistory.push(assistantMessage) - 1
      this.isStreaming = true
      const url = apiUrl(`/ai/agent/chat?chatId=${this.chatId}&agentId=${this.agentId}`)
      const controller = new AbortController()
      try {
        const response = await authenticatedFetch(url, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ prompt: msg }),
          signal: controller.signal
        })
        if (!response.ok) {
          const error = await response.json().catch(() => null)
          throw new Error(error?.msg || '对话请求失败')
        }
        if (!response.body) {
          this.chatHistory.pop()
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
          this.chatHistory[assistantMessageIndex].content = fullMsg
        }
      } catch (error) {
        if (!assistantMessage.content) this.chatHistory.pop()
        this.$message.error(error.message || '对话请求失败')
      } finally {
        this.isStreaming = false
      }
    },
    sendQuestion(q) {
      this.inputMsg = q
      this.sendMsg()
    },
    async saveAgent() {
      try {
        const { modelApiKeyConfigured, ...payload } = this.agentInfo
        const res = await axios.put(apiUrl(`/agent/update/${this.agentId}`), payload)
        if (res.data && res.data.code === 200) {
          this.agentInfo.modelApiKeyConfigured = this.agentInfo.modelApiKeyConfigured || Boolean(this.agentInfo.modelApiKey)
          this.agentInfo.modelApiKey = ''
          this.showModelConfig = false
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
  --workbench-rule: color-mix(in srgb, var(--sea-mist) 68%, var(--sea-muted));
  --workbench-canvas: var(--sea-paper);
  margin-top: 20px;
  display: flex;
  padding: 0;
  border: 1px solid color-mix(in srgb, var(--sea-mist) 72%, var(--sea-muted));
  border-radius: 10px;
  background: var(--workbench-canvas);
  box-shadow: none;
  overflow: hidden;
  width: 100%;
}

.detail-workbench--viewport {
  height: min(760px, calc(100dvh - 132px));
  min-height: 0;
  max-height: calc(100dvh - 132px);
}

.settings-pane {
  --form-label-width: 96px;
  flex: 0 0 43%;
  padding: 30px 34px 28px;
  border-right: 1px solid var(--workbench-rule);
  min-width: 340px;
  min-height: 0;
  overflow-y: auto;
  scrollbar-gutter: stable;
}
.settings-pane--flush {
  background: var(--sea-paper);
}
.form-section-label {
  display: grid;
  gap: 3px;
  margin: 0 0 18px;
}
.form-section-label span,
.knowledge-header-row > div > span {
  color: var(--sea-deep);
  font-size: 16px;
  font-weight: 700;
  letter-spacing: .02em;
}
.form-section-label small,
.knowledge-header-row small {
  color: var(--sea-muted);
  font-size: 12px;
  line-height: 1.5;
}
.form-section-label--conversation {
  margin-top: 30px;
  padding-top: 26px;
  border-top: 1px solid var(--workbench-rule);
}
.agent-form .el-form-item {
  margin-bottom: 18px;
}
.agent-form :deep(.el-form-item__label) {
  width: var(--form-label-width) !important;
  color: var(--sea-ink);
  font-size: 14px;
  font-weight: 600;
}
.agent-form :deep(.el-input__wrapper),
.chat-input :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--sea-muted) 34%, var(--sea-paper)) inset;
}
.agent-form :deep(.el-textarea__inner) {
  line-height: 1.65;
  resize: vertical;
}
.model-config-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 34px 0 14px;
  padding-top: 22px;
  border-top: 1px solid color-mix(in srgb, var(--sea-mist) 72%, var(--sea-muted));
}
.model-config-toggle > div {
  display: grid;
  gap: 3px;
}
.model-config-toggle span {
  color: var(--sea-deep);
  font-size: 14px;
  font-weight: 700;
  letter-spacing: .04em;
}
.model-config-toggle small,
.model-config-panel > p {
  color: var(--sea-muted);
  font-size: 12px;
  line-height: 1.5;
}
.model-config-panel {
  margin: 0 0 4px;
  padding: 2px 0 0;
}
.model-config-panel > p {
  margin: 0 0 14px;
}
.knowledge-header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin: 30px 0 12px;
  padding-top: 26px;
  border-top: 1px solid var(--workbench-rule);
}
.knowledge-header-row > div {
  display: grid;
  gap: 3px;
}
.knowledge-header-row .el-button {
  flex: 0 0 auto;
  min-height: 36px;
  padding-inline: 12px;
}
.knowledge-list {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  margin-bottom: 8px;
}
.knowledge-link {
  width: auto;
  max-width: min(100%, 220px);
  border: 0;
  border-left: 3px solid var(--sea-sand);
  border-radius: 0;
  background: color-mix(in srgb, var(--sea-mist) 56%, var(--sea-paper));
}
.knowledge-link:hover {
  border-left-color: var(--sea-signal);
  background: color-mix(in srgb, var(--sea-signal) 8%, var(--sea-paper));
}
.knowledge-link-content {
  display: flex;
  align-items: center;
  gap: 7px;
  width: 100%;
  min-height: 34px;
  padding: 3px 3px 3px 8px;
}
.kb-icon {
  flex: 0 0 auto;
  color: var(--sea-signal);
  font-size: 15px;
}
.kb-name {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  color: var(--sea-ink);
  font-size: 13px;
  font-weight: 600;
  line-height: 1.35;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.knowledge-link .el-button {
  flex: 0 0 auto;
  width: 26px;
  min-width: 26px;
  height: 26px;
  min-height: 26px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--sea-danger);
}
.knowledge-link .el-button:hover,
.knowledge-link .el-button:focus-visible {
  background: color-mix(in srgb, var(--sea-danger) 10%, transparent);
  color: var(--sea-danger);
}
.knowledge-link .el-button :deep(.el-icon) {
  font-size: 14px;
}
.preview-pane {
  flex: 1 1 57%;
  display: flex;
  min-width: 400px;
  min-height: 0;
  padding: 24px;
  background: color-mix(in srgb, var(--sea-mist) 36%, var(--sea-paper));
}
.preview-instrument {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--sea-mist) 68%, var(--sea-muted));
  border-radius: 9px;
  background: color-mix(in srgb, var(--sea-paper) 80%, var(--sea-mist));
  box-shadow: none;
}
.chat-header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 62px;
  padding: 12px 16px 12px 18px;
  border-bottom: 1px solid color-mix(in srgb, var(--sea-mist) 24%, transparent);
  background: var(--sea-deep);
  gap: 16px;
}
.chat-heading {
  display: grid;
  gap: 1px;
}
.chat-heading > span {
  color: color-mix(in srgb, var(--sea-mist) 72%, var(--sea-muted));
  font-family: 'JetBrains Mono', monospace;
  font-size: 11px;
  letter-spacing: .08em;
}
.chat-header {
  color: var(--sea-paper);
  font-size: 15px;
  font-weight: 700;
  letter-spacing: .04em;
}
.save-agent-btn {
  min-height: 36px;
  padding-inline: 15px;
  border: 0;
  color: var(--sea-deep);
  font-weight: 700;
  background: var(--sea-sand);
  box-shadow: none;
}
.save-agent-btn:hover,
.save-agent-btn:focus-visible {
  color: var(--sea-deep);
  background: color-mix(in srgb, var(--sea-sand) 82%, var(--sea-paper));
}
.chat-history {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 22px;
  background: color-mix(in srgb, var(--sea-mist) 58%, var(--sea-paper));
}
.chat-msg {
  display: flex;
  align-items: flex-start;
  margin-bottom: 16px;
}
.msg-content {
  display: flex;
  align-items: flex-start;
  max-width: min(85%, 620px);
}
.avatar-img {
  flex: 0 0 auto;
  width: 32px;
  height: 32px;
  margin: 2px 10px 0 0;
  border: 1px solid color-mix(in srgb, var(--sea-sand) 58%, var(--sea-paper));
  border-radius: 50%;
  background: var(--sea-paper);
  object-fit: cover;
}
.msg-bubble {
  width: auto;
  max-width: 100%;
  padding: 11px 13px;
  border: 1px solid color-mix(in srgb, var(--sea-muted) 24%, var(--sea-paper));
  border-radius: 5px 12px 12px;
  background: var(--sea-paper);
  box-shadow: 0 2px 5px color-mix(in srgb, var(--sea-deep) 5%, transparent);
  box-sizing: border-box;
  color: var(--sea-ink);
  font-size: 14px;
  line-height: 1.65;
  word-break: break-word;
}
.msg-bubble.user {
  border-color: var(--sea-deep);
  border-radius: 12px 5px 12px 12px;
  background: var(--sea-deep);
  color: var(--sea-paper);
  box-shadow: none;
}
.user {
  flex-direction: row-reverse;
  margin-left: auto;
}
.prologue-greeting {
  margin-bottom: 14px;
  color: var(--sea-ink);
  font-weight: 600;
}
.prologue-questions {
  display: grid;
  gap: 7px;
  width: 100%;
}
.question-chip {
  width: 100%;
  min-height: 34px;
  padding: 8px 10px;
  border: 1px solid color-mix(in srgb, var(--sea-signal) 40%, var(--sea-paper));
  border-radius: 6px;
  background: color-mix(in srgb, var(--sea-signal) 6%, var(--sea-paper));
  color: var(--sea-ink);
  cursor: pointer;
  font: inherit;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.45;
  text-align: left;
}
.question-chip:hover,
.question-chip:focus-visible {
  border-color: var(--sea-signal);
  background: color-mix(in srgb, var(--sea-signal) 13%, var(--sea-paper));
}
.question-chip-text {
  display: block;
}
.chat-input-row {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 8px;
  padding: 13px 14px;
  border-top: 1px solid color-mix(in srgb, var(--sea-mist) 64%, var(--sea-muted));
  background: var(--sea-paper);
}
.chat-input {
  flex: 1;
  min-width: 0;
}
.chat-input-row .el-button {
  flex: 0 0 auto;
  min-height: 40px;
  padding-inline: 15px;
}

@media (max-width: 720px) {
  .detail-workbench--viewport {
    height: auto;
    max-height: none;
    padding: 0;
    flex-direction: column;
  }

  .settings-pane,
  .preview-pane {
    flex-basis: auto;
    min-width: 0;
  }

  .settings-pane {
    padding: 26px 20px;
    border-right: 0;
    border-bottom: 1px solid var(--workbench-rule);
    overflow-y: visible;
  }

  .settings-pane { --form-label-width: 0px; }

  .form-section-label { margin-left: 0; }

  .agent-form :deep(.el-form-item__label) {
    width: auto !important;
  }

  .preview-pane {
    min-height: 0;
    padding: 12px;
  }

  .chat-history {
    flex: 0 0 auto;
    min-height: 260px;
    overflow-y: visible;
  }
}
</style>
