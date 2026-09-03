import { http } from './http'

const modelsRoot = '/embedding-evaluation/models'
const part = (value) => encodeURIComponent(String(value))
const root = (knowledgeId) => `/knowledge/${part(knowledgeId)}/embedding-evaluation`

// This module returns the payload, after checking the application's business envelope.
async function payload(request) {
  const response = await request
  const body = response?.data
  if (body?.code !== 200 && body?.code !== '200') {
    const error = new Error(body?.msg || '评测请求未成功，请重试。')
    error.response = response
    throw error
  }
  return body.data
}

export const listModels = () => payload(http.get(modelsRoot))
export const testModel = (command) => payload(http.post(`${modelsRoot}/test`, command))
export const createModel = (command) => payload(http.post(modelsRoot, command))
export const updateModel = (id, command) => payload(http.put(`${modelsRoot}/${part(id)}`, command))
export const deleteModel = (id) => payload(http.delete(`${modelsRoot}/${part(id)}`))
export const listChunks = (knowledgeId) => payload(http.get(`${root(knowledgeId)}/chunks`))
export const createSnapshot = (knowledgeId, command) =>
  payload(http.post(`${root(knowledgeId)}/snapshots`, command))
export const getSnapshot = (knowledgeId, id) =>
  payload(http.get(`${root(knowledgeId)}/snapshots/${part(id)}`))
export const listDatasets = (knowledgeId) => payload(http.get(`${root(knowledgeId)}/datasets`))
export const getDataset = (knowledgeId, id, revision) =>
  payload(
    http.get(`${root(knowledgeId)}/datasets/${part(id)}`, { params: revision == null ? {} : { revision } }),
  )
export const createDataset = (knowledgeId, command) =>
  payload(http.post(`${root(knowledgeId)}/datasets`, command))
export const updateDataset = (knowledgeId, id, command) =>
  payload(http.put(`${root(knowledgeId)}/datasets/${part(id)}`, command))
export const createRun = (knowledgeId, command) => payload(http.post(`${root(knowledgeId)}/runs`, command))
export const listRuns = (knowledgeId) => payload(http.get(`${root(knowledgeId)}/runs`))
export const getRun = (knowledgeId, id) => payload(http.get(`${root(knowledgeId)}/runs/${part(id)}`))
export const cancelRun = (knowledgeId, id) =>
  payload(http.post(`${root(knowledgeId)}/runs/${part(id)}/cancel`))
export const retryRun = (knowledgeId, id) => payload(http.post(`${root(knowledgeId)}/runs/${part(id)}/retry`))

export async function exportRun(knowledgeId, id, format) {
  if (!['json', 'csv', 'html'].includes(format)) throw new Error('不支持的导出格式')
  let response
  try {
    response = await http.get(`${root(knowledgeId)}/runs/${part(id)}/export`, {
      params: { format },
      responseType: 'blob',
    })
  } catch (error) {
    if (error.response?.data instanceof Blob) {
      const body = await error.response.data.text()
      try {
        const parsed = JSON.parse(body)
        error.message = parsed.msg || error.message
      } catch {
        /* Keep HTTP error. */
      }
    }
    throw error
  }
  const blob = response.data
  if (!(blob instanceof Blob)) throw new Error(blob?.msg || '报告文件未返回，请重试。')
  if (format === 'json' || blob.type.includes('json')) {
    let envelope
    try {
      envelope = JSON.parse(await blob.text())
    } catch {
      /* A non-JSON error is handled by HTTP status. */
    }
    if (envelope?.code != null && ![200, '200'].includes(envelope.code))
      throw new Error(envelope.msg || '导出失败')
  }
  return blob
}

export function downloadReport(blob, runId, format) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `embedding-evaluation-${String(runId).replace(/[^a-zA-Z0-9-]/g, '')}.${format}`
  document.body.appendChild(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
