const assetModules = import.meta.glob('@/assets/imgs/**/*', {
  eager: true,
  query: '?url',
  import: 'default'
})

const assetMap = Object.fromEntries(
  Object.entries(assetModules).map(([key, value]) => {
    const normalizedKey = key.replace(/^\/src\/assets/, '')
    return [normalizedKey, value]
  })
)

export function resolveAssetPath(path, fallback = '/imgs/icons/default-icon.png') {
  const target = normalizeAssetPath(path || fallback)
  if (!target) return ''
  if (target.startsWith('http://') || target.startsWith('https://')) return target
  const fallbackTarget = normalizeAssetPath(fallback)
  return assetMap[target] || assetMap[fallbackTarget] || target
}

function normalizeAssetPath(path) {
  if (!path) return ''
  const target = String(path).trim()
  if (target.startsWith('http://') || target.startsWith('https://')) return target
  if (target.startsWith('/src/assets/imgs/')) {
    return target.replace('/src/assets', '')
  }
  if (target.startsWith('/assets/imgs/')) {
    return target.replace('/assets', '')
  }
  if (target.startsWith('src/assets/imgs/')) {
    return target.replace('src/assets', '')
  }
  if (target.startsWith('assets/imgs/')) {
    return target.replace('assets', '')
  }
  if (target.startsWith('/types/')) return `/imgs${target}`
  if (target.startsWith('types/')) return `/imgs/${target}`
  if (target.startsWith('/imgs/')) return target
  if (target.startsWith('imgs/')) return `/${target}`
  if (target.startsWith('/')) return target
  return `/${target}`
}
