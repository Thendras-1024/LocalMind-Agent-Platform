export function resolveAssetPath(path, fallback = '/imgs/icons/default-icon.png') {
  if (!path) return fallback
  if (path.startsWith('http://') || path.startsWith('https://')) return path
  if (path.startsWith('/')) return path
  return `/${path}`
}
