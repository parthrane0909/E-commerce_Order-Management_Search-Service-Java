import { describe, expect, it } from 'vitest'
import { readFileSync, readdirSync, existsSync } from 'node:fs'
import { join, resolve } from 'node:path'

/**
 * The catalog seed resolves every product's `image_url` through
 * `backend/src/main/resources/catalog/image-pool.json`, but the artwork itself lives here in
 * `frontend/public/products/`. This test is the other half of that contract: every name the
 * backend can pick must be a file the browser can actually serve. If someone renames or
 * deletes an SVG, the seed fails at runtime — this makes it fail in CI instead.
 */

const repoRoot = resolve(__dirname, '..', '..')
const manifestPath = join(
  repoRoot,
  'backend',
  'src',
  'main',
  'resources',
  'catalog',
  'image-pool.json',
)
const publicDir = join(repoRoot, 'frontend', 'public', 'products')

const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'))
const onDisk = new Set(readdirSync(publicDir))

function collectNames(node, into = new Set()) {
  if (typeof node === 'string') {
    // Only artwork filenames count — `_readme` prose and group/category labels are strings too.
    if (/\.svg$/i.test(node)) into.add(node)
  } else if (Array.isArray(node)) {
    node.forEach((entry) => collectNames(entry, into))
  } else if (node && typeof node === 'object') {
    Object.values(node).forEach((entry) => collectNames(entry, into))
  }
  return into
}

describe('catalog image pool', () => {
  it('points at files that exist in frontend/public/products', () => {
    const referenced = [...collectNames(manifest)]
    expect(referenced.length).toBeGreaterThan(0)

    const missing = referenced.filter((name) => !onDisk.has(name))
    expect(missing, `referenced but not on disk: ${missing.join(', ')}`).toEqual([])
  })

  it('references only assets that really are served from /products', () => {
    for (const name of collectNames(manifest)) {
      expect(name).toMatch(/^[A-Za-z0-9._-]+\.svg$/)
      expect(existsSync(join(publicDir, name))).toBe(true)
    }
  })

  it('reuses a bounded pool instead of shipping one file per product', () => {
    const referenced = collectNames(manifest)
    // The seed targets 3000+ products; a shared pool of a few dozen is the whole design.
    expect(referenced.size).toBeLessThan(100)
    expect(referenced.size).toBeGreaterThanOrEqual(40)
  })

  it('maps every generator category to a non-empty image group', () => {
    const generator = readFileSync(
      join(
        repoRoot,
        'backend',
        'src',
        'main',
        'java',
        'com',
        'meridian',
        'backend',
        'products',
        'seed',
        'ProductCatalogGenerator.java',
      ),
      'utf8',
    )

    const keys = [...generator.matchAll(/new CategorySpec\("([a-z0-9-]+)"/g)].map((m) => m[1])
    expect(keys.length).toBeGreaterThanOrEqual(30)
    expect(new Set(keys).size, 'duplicate category key').toBe(keys.length)

    const categories = manifest.category_groups ?? {}
    const groups = manifest.groups ?? {}

    const unmapped = keys.filter((key) => typeof categories[key] !== 'string')
    expect(unmapped, `categories missing from category_groups: ${unmapped.join(', ')}`).toEqual([])

    const empty = keys.filter(
      (key) => !Array.isArray(groups[categories[key]]) || groups[categories[key]].length === 0,
    )
    expect(empty, `categories mapped to an empty image group: ${empty.join(', ')}`).toEqual([])
  })
})
