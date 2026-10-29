export function concat(...parts) {
  let n = 0
  for (const part of parts) n += part.length
  const out = new Uint8Array(n)
  let off = 0
  for (const part of parts) {
    out.set(part, off)
    off += part.length
  }
  return out
}

export function utf8(text) {
  return new TextEncoder().encode(text)
}

export function utf8Decode(bytes) {
  return new TextDecoder('utf-8', { fatal: true }).decode(bytes)
}

export function hex(bytes) {
  let out = ''
  for (const b of bytes) out += b.toString(16).padStart(2, '0')
  return out
}

export function unhex(text) {
  const clean = text.trim().toLowerCase()
  if (clean.length % 2 !== 0 || /[^0-9a-f]/.test(clean)) throw new Error('Invalid hex.')
  const out = new Uint8Array(clean.length / 2)
  for (let i = 0; i < out.length; i++) out[i] = parseInt(clean.slice(i * 2, i * 2 + 2), 16)
  return out
}

export function b64url(bytes) {
  let s = ''
  const bin = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes)
  const chunk = 0x8000
  for (let i = 0; i < bin.length; i += chunk) {
    s += String.fromCharCode(...bin.subarray(i, i + chunk))
  }
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '')
}

export function b64urlDecode(text) {
  const pad = text.length % 4 === 0 ? '' : '='.repeat(4 - (text.length % 4))
  const b64 = text.replace(/-/g, '+').replace(/_/g, '/') + pad
  const bin = atob(b64)
  const out = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
  return out
}

export function u16(n) {
  return Uint8Array.of((n >> 8) & 255, n & 255)
}

export function u24(n) {
  return Uint8Array.of((n >> 16) & 255, (n >> 8) & 255, n & 255)
}

export function u32(n) {
  return Uint8Array.of((n >>> 24) & 255, (n >>> 16) & 255, (n >>> 8) & 255, n & 255)
}

export function u64(n) {
  const x = typeof n === 'bigint' ? n : BigInt(n)
  const out = new Uint8Array(8)
  let v = x
  for (let i = 7; i >= 0; i--) {
    out[i] = Number(v & 0xffn)
    v >>= 8n
  }
  return out
}

export function readU16(b, o) {
  return (b[o] << 8) | b[o + 1]
}

export function readU24(b, o) {
  return (b[o] << 16) | (b[o + 1] << 8) | b[o + 2]
}

export function readU32(b, o) {
  return ((b[o] * 0x1000000) + (b[o + 1] << 16) + (b[o + 2] << 8) + b[o + 3]) >>> 0
}

export function readU64(b, o) {
  let n = 0n
  for (let i = 0; i < 8; i++) n = (n << 8n) | BigInt(b[o + i])
  return n
}

export function randomBytes(n) {
  const out = new Uint8Array(n)
  crypto.getRandomValues(out)
  return out
}

export function equalBytes(a, b) {
  if (a.length !== b.length) return false
  let d = 0
  for (let i = 0; i < a.length; i++) d |= a[i] ^ b[i]
  return d === 0
}

export function ascii(text) {
  const out = new Uint8Array(text.length)
  for (let i = 0; i < text.length; i++) out[i] = text.charCodeAt(i) & 255
  return out
}
