function normalizedType(chunk) {
  const type = String(chunk?.chunkType || '').trim().toUpperCase()
  if (type === 'PARENT' || type === 'CHILD' || type === 'SINGLE') return type
  return chunk?.parentPublicId ? 'CHILD' : 'SINGLE'
}

function byPosition(left, right) {
  return Number(left?.position || 0) - Number(right?.position || 0)
}

function bySiblingPosition(left, right) {
  const sibling = Number(left?.siblingPosition || 0) - Number(right?.siblingPosition || 0)
  return sibling || byPosition(left, right)
}

export function groupChunks(chunks) {
  const items = Array.isArray(chunks) ? [...chunks] : []
  const parentsById = new Map()
  const childrenByParentId = new Map()
  const singles = []
  const orphanChildren = []

  items.forEach(chunk => {
    const type = normalizedType(chunk)
    if (type === 'PARENT') parentsById.set(chunk.publicId, chunk)
    else if (type === 'CHILD') {
      const parentId = chunk.parentPublicId
      const children = childrenByParentId.get(parentId) || []
      children.push(chunk)
      childrenByParentId.set(parentId, children)
    } else singles.push(chunk)
  })

  const parents = [...parentsById.values()]
    .sort(byPosition)
    .map(parent => ({
      parent,
      children: (childrenByParentId.get(parent.publicId) || []).slice().sort(bySiblingPosition),
    }))

  childrenByParentId.forEach((children, parentId) => {
    if (!parentsById.has(parentId)) orphanChildren.push(...children)
  })

  const childCount = parents.reduce((count, group) => count + group.children.length, 0)
  return {
    hierarchical: parents.length > 0,
    parents,
    singles: singles.slice().sort(byPosition),
    orphanChildren: orphanChildren.slice().sort(byPosition),
    parentCount: parents.length,
    childCount,
    vectorCount: singles.length + childCount,
  }
}
