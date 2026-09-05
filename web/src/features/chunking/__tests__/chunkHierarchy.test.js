import { groupChunks } from '../chunkHierarchy'

const chunk = (overrides = {}) => ({
  publicId: 'chunk',
  position: 0,
  siblingPosition: 0,
  chunkType: 'SINGLE',
  content: '正文',
  ...overrides,
})

describe('groupChunks', () => {
  it('groups interleaved flat responses by parent id without mutating input and sorts children by sibling position', () => {
    const chunks = [
      chunk({ publicId: 'C2', position: 4, chunkType: 'CHILD', parentPublicId: 'P1', siblingPosition: 1 }),
      chunk({ publicId: 'P2', position: 5, chunkType: 'PARENT', siblingPosition: 1 }),
      chunk({ publicId: 'C1', position: 2, chunkType: 'CHILD', parentPublicId: 'P1', siblingPosition: 0 }),
      chunk({ publicId: 'P1', position: 1, chunkType: 'PARENT', siblingPosition: 0 }),
      chunk({ publicId: 'S1', position: 3, chunkType: 'SINGLE' }),
      chunk({ publicId: 'C3', position: 6, chunkType: 'CHILD', parentPublicId: 'P2', siblingPosition: 0 }),
      chunk({ publicId: 'ORPHAN', position: 7, chunkType: 'CHILD', parentPublicId: 'MISSING' }),
    ]
    const snapshot = JSON.parse(JSON.stringify(chunks))

    const result = groupChunks(chunks)

    expect(result.hierarchical).toBe(true)
    expect(result.parents.map(group => group.parent.publicId)).toEqual(['P1', 'P2'])
    expect(result.parents[0].children.map(item => item.publicId)).toEqual(['C1', 'C2'])
    expect(result.parents[1].children.map(item => item.publicId)).toEqual(['C3'])
    expect(result.singles.map(item => item.publicId)).toEqual(['S1'])
    expect(result.orphanChildren.map(item => item.publicId)).toEqual(['ORPHAN'])
    expect(result.parentCount).toBe(2)
    expect(result.childCount).toBe(3)
    expect(result.vectorCount).toBe(4)
    expect(chunks).toEqual(snapshot)
  })

  it('keeps single-only and unknown chunks flat unless an unknown chunk claims a parent relation', () => {
    const result = groupChunks([
      chunk({ publicId: 'S2', position: 2, siblingPosition: 2 }),
      chunk({ publicId: 'FUTURE', position: 1, chunkType: 'FUTURE_TYPE' }),
      chunk({ publicId: 'S1', position: 0, siblingPosition: 0 }),
      chunk({ publicId: 'BAD', position: 3, chunkType: 'FUTURE_TYPE', parentPublicId: 'P1' }),
    ])

    expect(result.hierarchical).toBe(false)
    expect(result.singles.map(item => item.publicId)).toEqual(['S1', 'FUTURE', 'S2'])
    expect(result.orphanChildren.map(item => item.publicId)).toEqual(['BAD'])
    expect(result.parentCount).toBe(0)
    expect(result.childCount).toBe(0)
    expect(result.vectorCount).toBe(3)
  })
})
