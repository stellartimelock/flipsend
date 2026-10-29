import { b64url, b64urlDecode, concat, hex, readU16, readU32, readU64, u16, u32, u64, unhex, utf8, utf8Decode } from './bytes.js'
import { sha256 } from './sha256.js'

export const VERSION = 1
export const HEADER = 12
export const MAX_FRAME = 262144
export const MAX_CHUNK = 65536
export const MAX_TEXT = 1048576
export const MAX_FILE = 8589934592
export const TCP_PORT = 53321
export const QR_SCHEME = 'stellarsend://pair'
export const DEFAULT_RELAY = 'wss://stellar-send-relay.onrender.com/v1'
export const WEB_ORIGIN = 'https://flipsend.stellartimelock.com'
export const FAIR_USE_BYTES = 100_000_000
export const FILE_MEMORY_BYTES = 48 * 1024 * 1024
export const FREE_RATE_BYTES_PER_MIN = 5 * 1024 * 1024
export const BYTES_PER_GB = 1_000_000_000
export const PACKAGE_NAME = 'org.stellartimelock.stellarsend'

export const Message = {
  Hello: 1,
  HelloAck: 2,
  Offer: 3,
  Accept: 4,
  Reject: 5,
  Chunk: 6,
  Cancel: 7,
  Complete: 8,
  Error: 9,
  Service: 10,
  ServiceResult: 11,
  TerminalData: 12
}

export class ProtocolError extends Error {
  constructor(message, code = 'bad_request') {
    super(message)
    this.code = code
  }
}

export class QuotaError extends Error {
  constructor(message) {
    super(message)
    this.code = 'quota'
  }
}

const RESERVED = new Set(['CON', 'PRN', 'AUX', 'NUL', 'COM1', 'COM2', 'COM3', 'COM4', 'COM5', 'COM6', 'COM7', 'COM8', 'COM9', 'LPT1', 'LPT2', 'LPT3', 'LPT4', 'LPT5', 'LPT6', 'LPT7', 'LPT8', 'LPT9'])

const CONTENT_TYPES = {
  mp4: 'video/mp4',
  mov: 'video/quicktime',
  m4v: 'video/x-m4v',
  webm: 'video/webm',
  mkv: 'video/x-matroska',
  avi: 'video/x-msvideo',
  '3gp': 'video/3gpp',
  mp3: 'audio/mpeg',
  m4a: 'audio/mp4',
  wav: 'audio/wav',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  png: 'image/png',
  gif: 'image/gif',
  webp: 'image/webp',
  heic: 'image/heic',
  pdf: 'application/pdf',
  apk: 'application/vnd.android.package-archive',
  txt: 'text/plain'
}

export function contentType(name, declared) {
  const given = String(declared || '').trim()
  const generic = !given || given === 'application/octet-stream' || given === '*/*' || given === 'application/*'
  if (!generic) return given.slice(0, 127)
  const leaf = String(name || '').split(/[/\\]/).pop() || ''
  const dot = leaf.lastIndexOf('.')
  const ext = dot > 0 ? leaf.slice(dot + 1).toLowerCase() : ''
  return CONTENT_TYPES[ext] || given || 'application/octet-stream'
}

export function sanitizeName(raw) {
  if (!raw || !String(raw).trim()) return 'file'
  const normalized = String(raw).replace(/\\/g, '/')
  const slash = normalized.lastIndexOf('/')
  let name = slash >= 0 ? normalized.slice(slash + 1) : normalized
  if (name === '.' || name === '..') return 'file'
  let cleaned = ''
  for (const c of name) {
    const code = c.charCodeAt(0)
    cleaned += code < 32 || '<>:"/\\|?*'.includes(c) ? '_' : c
  }
  cleaned = cleaned.trim().replace(/[. ]+$/g, '')
  if (!cleaned) return 'file'
  const dot = cleaned.lastIndexOf('.')
  const stem = dot > 0 ? cleaned.slice(0, dot) : cleaned
  if (RESERVED.has(stem.toUpperCase())) cleaned = '_' + cleaned
  if (cleaned.length > 240) {
    let ext = cleaned.lastIndexOf('.') > 0 ? cleaned.slice(cleaned.lastIndexOf('.')) : ''
    if (ext.length > 20) ext = ''
    cleaned = cleaned.slice(0, 240 - ext.length) + ext
  }
  return cleaned
}

export function encodeFrame(type, payload, flags = 0) {
  if (payload.length > MAX_FRAME) throw new ProtocolError('Frame payload exceeds the limit.', 'limit')
  const out = new Uint8Array(HEADER + payload.length)
  out.set(utf8('SSND'))
  out[4] = VERSION
  out[5] = type
  out.set(u16(flags), 6)
  out.set(u32(payload.length), 8)
  out.set(payload, HEADER)
  return out
}

export function decodeFrame(bytes) {
  if (bytes.length < HEADER) throw new ProtocolError('Unrecognized protocol header.')
  if (utf8Decode(bytes.subarray(0, 4)) !== 'SSND') throw new ProtocolError('Unrecognized protocol header.')
  if (bytes[4] !== VERSION) throw new ProtocolError('Unsupported protocol version.')
  const type = bytes[5]
  if (type < 1 || type > 12) throw new ProtocolError('Unknown message type.')
  const flags = readU16(bytes, 6)
  if (flags !== 0) throw new ProtocolError('Unsupported frame flags.')
  const length = readU32(bytes, 8)
  if (length > MAX_FRAME) throw new ProtocolError('Frame payload exceeds the limit.', 'limit')
  if (bytes.length !== HEADER + length) throw new ProtocolError('Unrecognized protocol header.')
  return { version: bytes[4], type, flags, payload: bytes.subarray(HEADER) }
}

export function uuidBytes(id) {
  return unhex(id.replace(/-/g, ''))
}

export function bytesToUuid(bytes) {
  const h = hex(bytes.subarray(0, 16))
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`
}

export function encodeChunk(transferId, offset, data) {
  if (data.length > MAX_CHUNK) throw new ProtocolError('Chunk exceeds the limit.', 'limit')
  const out = new Uint8Array(24 + data.length)
  out.set(uuidBytes(transferId))
  out.set(u64(offset), 16)
  out.set(data, 24)
  return out
}

export function decodeChunk(payload) {
  if (payload.length < 24) throw new ProtocolError('Chunk is too short.')
  if (payload.length - 24 > MAX_CHUNK) throw new ProtocolError('Chunk exceeds the limit.', 'limit')
  return {
    transferId: bytesToUuid(payload),
    offset: readU64(payload, 16),
    data: payload.subarray(24)
  }
}

export function jsonBytes(value) {
  return utf8(JSON.stringify(value))
}

export function parseJson(payload) {
  try {
    return JSON.parse(utf8Decode(payload))
  } catch {
    throw new ProtocolError('Unreadable protocol message.')
  }
}

export function helloBody(identity, extra = {}) {
  const body = {
    v: VERSION,
    deviceId: identity.deviceId,
    name: identity.name,
    fingerprint: identity.fingerprint
  }
  if (extra.plan) body.plan = extra.plan
  if (extra.quotaKey) body.quotaKey = extra.quotaKey
  if (extra.relayUsed != null) body.relayUsed = extra.relayUsed
  if (extra.relayLimit != null) body.relayLimit = extra.relayLimit
  return body
}

export function sha256HexText(text) {
  return hex(sha256(utf8(text)))
}

export function roomHash(label) {
  return hex(sha256(utf8(label)))
}

export function pairedRoom(a, b) {
  const left = a.toLowerCase()
  const right = b.toLowerCase()
  const [lo, hi] = left < right ? [left, right] : [right, left]
  return roomHash(`stellarsend-room-v1|${lo}|${hi}`)
}

export function pairingRoom(showingId) {
  return roomHash(`stellarsend-pair-v1|${showingId.toLowerCase()}`)
}

export function entitlementKey(deviceId) {
  return roomHash(`stellarsend-quota-v1|${deviceId}`)
}

export function shortCode(fingerprint) {
  if (!isPin(fingerprint)) throw new ProtocolError('Invalid fingerprint.')
  return (fingerprint.slice(0, 4) + '-' + fingerprint.slice(4, 8)).toUpperCase()
}

export function isPin(value) {
  return typeof value === 'string' && /^[0-9a-f]{64}$/.test(value)
}

const FORBIDDEN = new Set(['secret', 'private', 'priv', 'sk', 'password', 'token', 'seed', 'mnemonic', 'pkey'])

function escapeQuery(value) {
  return encodeURIComponent(value).replace(/[!'()*]/g, (c) => '%' + c.charCodeAt(0).toString(16).toUpperCase())
}

export function pairingToQr(advert) {
  if (advert.version !== 1) throw new ProtocolError('Unsupported pairing code.')
  const candidates = (advert.ips || []).filter(Boolean)
  const host = advert.host || candidates[0] || ''
  const parts = [
    'v=1',
    'id=' + escapeQuery(advert.deviceId),
    'pk=' + escapeQuery(advert.pk),
    'fp=' + escapeQuery(advert.fp),
    'host=' + escapeQuery(host),
    'port=' + String(advert.port),
    'name=' + escapeQuery(advert.name)
  ]
  if (candidates.length) parts.push('ips=' + escapeQuery(candidates.join(',')))
  return QR_SCHEME + '?' + parts.join('&')
}

export function parsePairing(payload) {
  const trimmed = String(payload || '').trim()
  if (!trimmed.startsWith(QR_SCHEME + '?')) throw new ProtocolError('This is not a FlipSend pairing code.')
  const fields = new Map()
  for (const part of trimmed.slice(QR_SCHEME.length + 1).split('&')) {
    if (!part) continue
    const eq = part.indexOf('=')
    const key = decodeURIComponent(eq < 0 ? part : part.slice(0, eq))
    const raw = eq < 0 ? '' : part.slice(eq + 1)
    const value = decodeURIComponent(raw.replace(/\+/g, '%2B'))
    if (fields.has(key)) throw new ProtocolError('Pairing code has a duplicate field.')
    fields.set(key, value)
  }
  for (const key of fields.keys()) {
    if (FORBIDDEN.has(key.toLowerCase())) throw new ProtocolError('Pairing code contains a forbidden field.')
  }
  if (fields.get('v') !== '1') throw new ProtocolError('Unsupported pairing code.')
  const deviceId = fields.get('id') || ''
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(deviceId)) {
    throw new ProtocolError('Pairing code is missing a device id.')
  }
  const pk = fields.get('pk')
  const fp = fields.get('fp')
  if (!pk || !fp) throw new ProtocolError('Pairing code is missing a public key.')
  if (/private|begin/i.test(pk)) throw new ProtocolError('Pairing code must not contain a private key.')
  let spki
  try {
    spki = b64urlDecode(pk)
  } catch {
    throw new ProtocolError('Pairing public key is not valid.')
  }
  if (!isPin(fp.toLowerCase())) throw new ProtocolError('Pairing public key is not valid.')
  const actual = hex(sha256(spki))
  if (actual !== fp.toLowerCase()) throw new ProtocolError('Pairing public key does not match its fingerprint.')
  const port = Number(fields.get('port') || TCP_PORT)
  const ips = (fields.get('ips') || '').split(',').map((s) => s.trim()).filter(Boolean)
  return {
    version: 1,
    deviceId: deviceId.toLowerCase(),
    pk,
    fp: fp.toLowerCase(),
    spki,
    host: fields.get('host') || '',
    port: Number.isFinite(port) ? port : TCP_PORT,
    name: fields.get('name') || 'Device',
    ips
  }
}

export function formatGb(bytes) {
  const n = bytes < 0 ? 0 : bytes
  const gb = Math.round((n / BYTES_PER_GB) * 100) / 100
  return String(gb).replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '') + ' GB'
}

export function personalLimit(reported) {
  if (!reported || reported <= 0) return FAIR_USE_BYTES
  if (reported >= 10 * 1024 * 1024 * 1024) return FAIR_USE_BYTES
  return reported
}

export function formatAmount(bytes) {
  const n = bytes < 0 ? 0 : bytes
  if (n < BYTES_PER_GB) {
    const mb = Math.round((n / 1_000_000) * 10) / 10
    return String(mb).replace(/\.0$/, '') + ' MB'
  }
  return formatGb(n)
}

export function usageLabel(used, limit) {
  const cap = personalLimit(limit)
  if (!cap || cap <= 0) return 'Usage appears after the relay answers.'
  return `${formatAmount(used)} of ${formatAmount(cap)} relay this month`
}

export function fairUsePause(limitBytes) {
  return 'This month\'s relay data is used up. Wi-Fi transfers still work.'
}

export const QUOTA_MESSAGE = 'This month\'s relay data is used up. Wi-Fi transfers still work.'
export const BUSY_MESSAGE = 'Relay busy, Wi-Fi transfers still work'

export function terminalMessage(id, command, confirmed) {
  return utf8(`flipsend-terminal-v1\n${id}\n${command}\n${confirmed ? '1' : '0'}\n`)
}

export function relayUrl(raw) {
  const text = (raw || DEFAULT_RELAY).trim()
  let url
  try {
    url = new URL(text)
  } catch {
    throw new ProtocolError('Relay address is not valid.')
  }
  const secure = url.protocol === 'wss:' || url.protocol === 'https:'
  const insecure = url.protocol === 'ws:' || url.protocol === 'http:'
  if (!secure && !insecure) throw new ProtocolError('Relay address is not valid.')
  const host = url.hostname
  if (!secure && host !== 'localhost' && host !== '127.0.0.1') throw new ProtocolError('Relay address is not valid.')
  const scheme = secure ? 'wss:' : 'ws:'
  const port = url.port ? `:${url.port}` : ''
  let path = url.pathname
  if (!path || path === '/') path = '/v1'
  return `${scheme}//${host}${port}${path}`
}

export function joinMessage(room, role, credential) {
  const body = { v: 1, room, role }
  writeCredential(body, credential)
  return JSON.stringify(body)
}

export function usageMessage(credential) {
  const body = { v: 1, op: 'usage' }
  writeCredential(body, credential)
  return JSON.stringify(body)
}

function writeCredential(body, credential) {
  if (!credential) return
  if (credential.purchaseToken) body.purchaseToken = credential.purchaseToken
  if (credential.productId) body.productId = credential.productId
  if (credential.basePlanId) body.basePlanId = credential.basePlanId
  if (credential.packageName) body.packageName = credential.packageName
  if (credential.entitlementKey) body.entitlementKey = credential.entitlementKey
}

export function meterFrom(json) {
  const msg = typeof json === 'string' ? JSON.parse(json) : json
  return {
    ok: msg.ok === true,
    error: msg.error || null,
    message: msg.message || null,
    tier: msg.tier || 'free',
    used: Number(msg.used || 0),
    limit: Number(msg.limit || 0),
    enforced: msg.enforced === true,
    resets: msg.resets || null
  }
}

export function overCap(meter) {
  return !!(meter && meter.enforced && meter.limit > 0 && meter.used >= meter.limit)
}

export { concat, hex, b64url }
