import { b64url, b64urlDecode, randomBytes, utf8, utf8Decode } from './bytes.js'

/** Kept for tests only. The live page never asks for a passphrase. */
export const VAULT_ITERATIONS = 210000

export async function sealVault(passphrase, payload, options = {}) {
  const iterations = options.iterations || VAULT_ITERATIONS
  const salt = options.salt || randomBytes(16)
  const iv = randomBytes(12)
  const key = await derive(passphrase, salt, iterations)
  const ct = new Uint8Array(await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv },
    key,
    utf8(JSON.stringify(payload))
  ))
  return {
    v: 1,
    kdf: 'PBKDF2-SHA256',
    iterations,
    salt: b64url(salt),
    iv: b64url(iv),
    ct: b64url(ct)
  }
}

export async function openVault(passphrase, record) {
  const key = await derive(passphrase, b64urlDecode(record.salt), record.iterations)
  try {
    const plain = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: b64urlDecode(record.iv) },
      key,
      b64urlDecode(record.ct)
    )
    return JSON.parse(utf8Decode(new Uint8Array(plain)))
  } catch {
    throw new Error('That passphrase did not unlock this browser\'s pairing.')
  }
}

async function derive(passphrase, salt, iterations) {
  const base = await crypto.subtle.importKey('raw', utf8(passphrase), 'PBKDF2', false, ['deriveKey'])
  return crypto.subtle.deriveKey(
    { name: 'PBKDF2', salt, iterations, hash: 'SHA-256' },
    base,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt']
  )
}

function db() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open('flipsend', 1)
    request.onupgradeneeded = () => {
      const database = request.result
      if (!database.objectStoreNames.contains('kv')) database.createObjectStore('kv')
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
}

export async function idbGet(key) {
  const database = await db()
  return new Promise((resolve, reject) => {
    const tx = database.transaction('kv', 'readonly')
    const request = tx.objectStore('kv').get(key)
    request.onsuccess = () => resolve(request.result ?? null)
    request.onerror = () => reject(request.error)
  })
}

export async function idbSet(key, value) {
  const database = await db()
  return new Promise((resolve, reject) => {
    const tx = database.transaction('kv', 'readwrite')
    tx.objectStore('kv').put(value, key)
    tx.oncomplete = () => resolve()
    tx.onerror = () => reject(tx.error)
  })
}

export async function idbDelete(key) {
  const database = await db()
  return new Promise((resolve, reject) => {
    const tx = database.transaction('kv', 'readwrite')
    tx.objectStore('kv').delete(key)
    tx.oncomplete = () => resolve()
    tx.onerror = () => reject(tx.error)
  })
}
