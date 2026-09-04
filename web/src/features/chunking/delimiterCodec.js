export function countUnicodeCodePoints(value) {
  return Array.from(String(value ?? '')).length
}

export function encodeDelimiter(value) {
  return String(value ?? '')
    .replace(/\\/g, '\\\\')
    .replace(/\n/g, '\\n')
    .replace(/\r/g, '\\r')
    .replace(/\t/g, '\\t')
}

export function decodeDelimiter(value) {
  const input = String(value ?? '')
  let decoded = ''
  for (let index = 0; index < input.length; index += 1) {
    const character = input[index]
    if (character !== '\\' || index === input.length - 1) {
      decoded += character
      continue
    }
    const escaped = input[index + 1]
    if (escaped === 'n') decoded += '\n'
    else if (escaped === 'r') decoded += '\r'
    else if (escaped === 't') decoded += '\t'
    else if (escaped === '\\') decoded += '\\'
    else decoded += `\\${escaped}`
    index += 1
  }
  return decoded
}
