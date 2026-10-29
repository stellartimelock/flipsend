import { concat, utf8 } from './bytes.js'

export function derLen(n) {
  if (n < 0x80) return Uint8Array.of(n)
  if (n <= 0xff) return Uint8Array.of(0x81, n)
  if (n <= 0xffff) return Uint8Array.of(0x82, (n >> 8) & 255, n & 255)
  return Uint8Array.of(0x83, (n >> 16) & 255, (n >> 8) & 255, n & 255)
}

export function tl(tag, body) {
  return concat(Uint8Array.of(tag), derLen(body.length), body)
}

export function seq(...parts) {
  return tl(0x30, concat(...parts))
}

export function setOf(...parts) {
  return tl(0x31, concat(...parts))
}

export function oid(dotted) {
  const nums = dotted.split('.').map(Number)
  const bytes = [40 * nums[0] + nums[1]]
  for (const n of nums.slice(2)) {
    const stack = [n & 0x7f]
    let v = n >> 7
    while (v > 0) {
      stack.push((v & 0x7f) | 0x80)
      v >>= 7
    }
    while (stack.length) bytes.push(stack.pop())
  }
  return tl(0x06, Uint8Array.from(bytes))
}

export function utf8String(text) {
  return tl(0x0c, utf8(text))
}

export function bitString(payload, unused = 0) {
  return tl(0x03, concat(Uint8Array.of(unused), payload))
}

export function octetString(body) {
  return tl(0x04, body)
}

export function integer(bytes) {
  let body = bytes
  let i = 0
  while (i < body.length - 1 && body[i] === 0) i++
  body = body.subarray(i)
  if (body[0] & 0x80) body = concat(Uint8Array.of(0), body)
  return tl(0x02, body)
}

export function utcTime(date) {
  const p = (n) => String(n).padStart(2, '0')
  const text = p(date.getUTCFullYear() % 100) + p(date.getUTCMonth() + 1) + p(date.getUTCDate())
    + p(date.getUTCHours()) + p(date.getUTCMinutes()) + p(date.getUTCSeconds()) + 'Z'
  return tl(0x17, utf8(text))
}

export function readDer(buf, offset = 0) {
  if (offset >= buf.length) throw new Error('Short certificate.')
  const tag = buf[offset]
  let p = offset + 1
  let len = buf[p++]
  if (len & 0x80) {
    const count = len & 0x7f
    if (count === 0 || count > 4 || p + count > buf.length) throw new Error('Bad certificate length.')
    len = 0
    for (let i = 0; i < count; i++) len = (len << 8) | buf[p++]
  }
  const end = p + len
  if (end > buf.length) throw new Error('Short certificate.')
  return { tag, start: offset, value: p, end, body: buf.subarray(p, end), bytes: buf.subarray(offset, end) }
}

export function p1363ToDer(sig) {
  if (sig.length !== 64) throw new Error('Unexpected signature length.')
  const intPart = (bytes) => {
    let i = 0
    while (i < bytes.length - 1 && bytes[i] === 0) i++
    let v = bytes.subarray(i)
    if (v[0] & 0x80) v = concat(Uint8Array.of(0), v)
    return tl(0x02, v)
  }
  return seq(intPart(sig.subarray(0, 32)), intPart(sig.subarray(32)))
}

export function derToP1363(der) {
  const outer = readDer(der, 0)
  if (outer.tag !== 0x30) throw new Error('Bad signature.')
  const r = readDer(der, outer.value)
  const s = readDer(der, r.end)
  const pad = (body) => {
    let v = body
    if (v[0] === 0) v = v.subarray(1)
    if (v.length > 32) throw new Error('Bad signature.')
    const out = new Uint8Array(32)
    out.set(v, 32 - v.length)
    return out
  }
  return concat(pad(r.body), pad(s.body))
}
