import { http } from './http'

function filePath(knowledgeId, fileId, suffix = '') {
  return `/knowledge/${knowledgeId}/files/${fileId}${suffix}`
}

export function getStrategies(knowledgeId, fileId) {
  return http.get(filePath(knowledgeId, fileId, '/chunk-strategies'))
}

export function createPreview(knowledgeId, fileId, request) {
  return http.post(filePath(knowledgeId, fileId, '/chunk-preview'), request)
}

export function getProcessing(knowledgeId, fileId) {
  return http.get(filePath(knowledgeId, fileId, '/processing'))
}

export function getChunks(knowledgeId, fileId) {
  return http.get(filePath(knowledgeId, fileId, '/chunks'))
}

export function updateChunk(knowledgeId, fileId, chunkPublicId, request) {
  const legacyTokenLimit = request?.overlapLimit == null && request?.overlapTokenLimit != null
  const overlap = legacyTokenLimit
    ? { overlapTokenLimit: request.overlapTokenLimit }
    : { overlapLimit: request?.overlapLimit, overlapUnit: request?.overlapUnit }
  return http.patch(filePath(knowledgeId, fileId, `/chunks/${chunkPublicId}`), {
    content: request?.content,
    overlapEnabled: request?.overlapEnabled,
    ...overlap,
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
