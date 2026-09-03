import { describe, expect, it } from 'vitest'
import {
  alignChunks,
  formatScore,
  formatPercent,
  newQuestion,
  validateQuestions,
  questionGap,
} from '../evaluationState'

describe('evaluation evidence semantics', () => {
  it('preserves zero and negative cosine values while missing metrics stay unknown', () => {
    expect(formatScore(0)).toBe('0.0000')
    expect(formatScore(-0.123456)).toBe('-0.1235')
    expect(formatScore(null)).toBe('未计算')
    expect(formatPercent(0)).toBe('0.0%')
    expect(formatPercent(null)).toBe('无适用样本')
  })
  it('aligns the union by chunk and retains judged evidence outside top K without inventing scores', () => {
    const models = [
      {
        modelId: 'a',
        queries: [
          {
            questionId: 'q',
            hits: [{ chunkId: 'c1', rank: 1, score: 0 }],
            judgedScores: [{ chunkId: 'c2', score: -0.2 }],
          },
        ],
      },
      { modelId: 'b', queries: [{ questionId: 'q', hits: [{ chunkId: 'c2', rank: 1, score: 0.7 }] }] },
    ]
    const rows = alignChunks(models, 'q')
    expect(rows).toHaveLength(2)
    expect(rows.find((row) => row.chunkId === 'c1').scores.a.score).toBe(0)
    expect(rows.find((row) => row.chunkId === 'c1').scores.b).toBeUndefined()
    expect(rows.find((row) => row.chunkId === 'c2').scores.a.rank).toBeUndefined()
  })
  it('does not turn unknown labels into negatives or invent a gap with missing evidence', () => {
    expect(questionGap({ hits: [{ label: 2, score: 0.56 }, { score: 0.54 }] }, true)).toBeNull()
    expect(
      questionGap(
        {
          hits: [
            { label: 2, score: 0 },
            { label: 0, score: -0.2 },
          ],
        },
        true,
      ),
    ).toBeCloseTo(0.2)
    expect(
      questionGap(
        {
          hits: [
            { label: 2, score: 0 },
            { label: 0, score: -0.2 },
          ],
        },
        false,
      ),
    ).toBeNull()
    expect(newQuestion().labels).toEqual({})
  })
  it('rejects intent leakage and hard negatives that are not labeled zero', () => {
    const base = { ...newQuestion(), query: '原问法', intentGroup: 'g' }
    expect(validateQuestions([base, { ...base, id: 'q2', split: 'ACCEPTANCE' }])).toContain('意图组')
    expect(validateQuestions([{ ...base, labels: { c: 1 }, hardNegativeIds: ['c'] }])).toContain('困难负例')
  })
})

it('marks a runtime baseline encoding change even when its read-only revision stays zero', async () => {
  const { modelSignature } = await import('../evaluationState')
  const model = { id: 'current', revision: 0, modelName: 'bge:latest', queryPrefix: '' }
  expect(modelSignature(model)).not.toEqual(modelSignature({ ...model, queryPrefix: '检索：' }))
})
