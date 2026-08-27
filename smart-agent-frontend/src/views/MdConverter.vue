<template>
  <div class="md-converter tool-panel">
    <div class="converter-header">
      <h3>MD文件生成</h3>
      <el-input
        v-model="fileName"
        placeholder="请输入MD文件标题"
        class="title-input"
      />
    </div>
    <div class="converter-content">
      <el-input
        v-model="mdContent"
        type="textarea"
        :rows="20"
        placeholder="请输入MD内容"
        class="md-editor"
      />
    </div>
    <div class="converter-footer">
      <el-button data-testid="convert-markdown" type="primary" @click="convertToMd">生成为md</el-button>
    </div>
  </div>
</template>

<script>
import { ElMessage } from 'element-plus'
import axios from 'axios'
import { apiUrl } from '../api/http'

export default {
  name: 'MdConverter',
  data() {
    return {
      fileName: '',
      mdContent: ''
    }
  },
  methods: {
    async convertToMd() {
      if (!this.fileName) {
        ElMessage.warning('请输入文件标题')
        return
      }
      if (!this.mdContent) {
        ElMessage.warning('请输入MD内容')
        return
      }

      try {
        const response = await axios.post(apiUrl('/tool/convmd'),
          this.mdContent,
          { 
            params: { fileName: this.fileName },
            headers: {
              'Content-Type': 'text/plain;charset=UTF-8'
            }
          }
        )
        
        if (response.data.code === 200) {
          ElMessage.success(`文件已保存至: ${response.data.data}`)
        } else {
          ElMessage.error(response.data.msg || '生成失败')
        }
      } catch (error) {
        ElMessage.error('生成失败，请检查网络连接')
        console.error('Error:', error)
      }
    }
  }
}
</script>

<style scoped>
.md-converter {
  min-height: 100%;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.converter-header {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.converter-header h3 {
  margin: 0;
  color: var(--sea-deep);
  font-family: 'Noto Serif SC', serif;
  font-size: 20px;
  font-weight: 700;
}

.title-input {
  width: 100%;
  max-width: 500px;
}

.converter-content {
  flex: 1;
  min-height: 460px;
  border: 1px solid color-mix(in srgb, var(--sea-mist) 72%, var(--sea-muted));
  border-radius: 8px;
  background: color-mix(in srgb, var(--sea-mist) 38%, var(--sea-paper));
  padding: 12px;
}

.md-editor {
  width: 100%;
  height: 100%;
}

.md-editor :deep(.el-textarea__inner) {
  height: 100%;
  font-family: 'Courier New', Courier, monospace;
  resize: none;
  background: var(--sea-paper);
  border: 1px solid color-mix(in srgb, var(--sea-mist) 72%, var(--sea-muted));
  border-radius: 6px;
}

.converter-footer {
  padding: 4px 0 0;
}

@media (max-width: 720px) {
  .md-converter { min-height: 50vh; }
  .converter-content { min-height: 50vh; }
}
</style>
