export function normalizeFileType(value) {
  return String(value ?? '').trim().toLowerCase().replace(/^\.+/, '')
}

export function normalizeStrategyCode(value) {
  return String(value ?? '').trim().toUpperCase()
}
