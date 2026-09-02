import { http } from './http'
import { normalizeStrategyCode } from '../features/chunking/normalization'

const localOnlyStrategyCodes = new Set(['GENERAL', 'PARENT_CHILD'])

function filePath(knowledgeId, fileId, suffix = '') {
  return `/knowledge/${knowledgeId}/files/${fileId}${suffix}`
}

function requireBackendStrategy(strategyCode) {
  if (localOnlyStrategyCodes.has(normalizeStrategyCode(strategyCode))) {
    throw new Error('该分块策略暂未开放，不能提交到后端')
  }
}

export function getStrategies(knowledgeId, fileId) {
  return http.get(filePath(knowledgeId, fileId, '/chunk-strategies'))
}

export function createPreview(knowledgeId, fileId, request) {
  requireBackendStrategy(request?.strategyCode)
  return http.post(filePath(knowledgeId, fileId, '/chunk-preview'), request)
}

export function getProcessing(knowledgeId, fileId) {
  return http.get(filePath(knowledgeId, fileId, '/processing'))
}

export function getChunks(knowledgeId, fileId) {
  return http.get(filePath(knowledgeId, fileId, '/chunks'))
}

export function updateChunk(knowledgeId, fileId, chunkPublicId, request) {
  return http.patch(filePath(knowledgeId, fileId, `/chunks/${chunkPublicId}`), {
    content: request?.content,
    overlapEnabled: request?.overlapEnabled,
    overlapTokenLimit: request?.overlapTokenLimit,
    lockVersion: request?.lockVersion,
  })
}

export function deleteChunk(knowledgeId, fileId, chunkPublicId, lockVersion) {
  return http.delete(filePath(knowledgeId, fileId, `/chunks/${chunkPublicId}`), {
    params: { lockVersion },
  })
}

export function confirmVectorization(knowledgeId, fileId, request) {
  return http.post(filePath(knowledgeId, fileId, '/confirm'), {
    lockVersion: request?.lockVersion,
  })
}

export function reindexChunk(knowledgeId, fileId, chunkPublicId) {
  return http.post(filePath(knowledgeId, fileId, `/chunks/${chunkPublicId}/reindex`))
}
