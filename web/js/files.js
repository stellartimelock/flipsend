import { FILE_MEMORY_BYTES } from './protocol.js'

const MEMORY_LIMIT = FILE_MEMORY_BYTES

export async function openStore() {
  try {
    if (!navigator.storage?.getDirectory) return memoryStore()
    const root = await navigator.storage.getDirectory()
    const partials = await root.getDirectoryHandle('partials', { create: true })
    const inbox = await root.getDirectoryHandle('inbox', { create: true })
    return {
      kind: 'opfs',
      async writeAt(name, offset, bytes) {
        const handle = await partials.getFileHandle(name, { create: true })
        const writable = await handle.createWritable({ keepExistingData: true })
        if (offset === 0) await writable.truncate(0)
        await writable.seek(offset)
        await writable.write(bytes)
        await writable.close()
      },
      async size(name) {
        try {
          const handle = await partials.getFileHandle(name)
          return (await handle.getFile()).size
        } catch {
          return 0
        }
      },
      async commit(name, id) {
        const handle = await partials.getFileHandle(name)
        const file = await handle.getFile()
        const dest = await inbox.getFileHandle(id, { create: true })
        const writable = await dest.createWritable()
        await writable.write(file)
        await writable.close()
        await partials.removeEntry(name).catch(() => {})
        return dest
      },
      async file(id) {
        const handle = await inbox.getFileHandle(id)
        return handle.getFile()
      },
      async drop(name) {
        await partials.removeEntry(name).catch(() => {})
      }
    }
  } catch {
    return memoryStore()
  }
}

function memoryStore() {
  const partials = new Map()
  const inbox = new Map()
  return {
    kind: 'memory',
    async writeAt(name, offset, bytes) {
      const current = partials.get(name) || []
      const size = current.reduce((sum, part) => sum + part.length, 0)
      if (offset === 0) partials.set(name, [bytes.slice()])
      else if (offset === size) {
        if (size + bytes.length > MEMORY_LIMIT) {
          throw new Error('Hit the 48 MB per-file browser cap. Send a smaller file, or use FlipSend on Android and Windows on the same Wi-Fi. This cap does not reset. The 50 GB figure is the shared free pool, not your allowance.')
        }
        current.push(bytes.slice())
        partials.set(name, current)
      } else {
        throw new Error('Could not resume the transfer.')
      }
    },
    async size(name) {
      return (partials.get(name) || []).reduce((sum, part) => sum + part.length, 0)
    },
    async commit(name, id) {
      const parts = partials.get(name) || []
      const blob = new Blob(parts)
      inbox.set(id, blob)
      partials.delete(name)
      return id
    },
    async file(id) {
      const blob = inbox.get(id)
      if (!blob) throw new Error('That file is no longer in this browser.')
      return blob
    },
    async drop(name) {
      partials.delete(name)
    }
  }
}
