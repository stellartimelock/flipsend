import { derToP1363, p1363ToDer } from './asn1.js'
import { concat, equalBytes, randomBytes, readU16, readU24, u16, u24, utf8 } from './bytes.js'
import { publicKeyFromCert } from './cert.js'
import { sha256 } from './sha256.js'

const TLS_AES = 0xc02b
const HS = {
  ClientHello: 1,
  ServerHello: 2,
  Certificate: 11,
  ServerKeyExchange: 12,
  CertificateRequest: 13,
  ServerHelloDone: 14,
  CertificateVerify: 15,
  ClientKeyExchange: 16,
  Finished: 20
}

const ALERTS = {
  0: 'close_notify',
  10: 'unexpected_message',
  20: 'bad_record_mac',
  40: 'handshake_failure',
  42: 'bad_certificate',
  47: 'certificate_unknown',
  51: 'decrypt_error',
  70: 'protocol_version',
  80: 'internal_error',
  112: 'unrecognized_name'
}

export class TlsError extends Error {
  constructor(message, alert) {
    super(message)
    this.alert = alert
  }
}

class Queue {
  constructor(read) {
    this.read = read
    this.parts = []
    this.off = 0
    this.total = 0
  }

  async pull(n) {
    while (this.total < n) {
      const chunk = await this.read()
      if (!chunk || chunk.length === 0) {
        const error = new Error('Connection closed.')
        error.code = 'eof'
        throw error
      }
      this.parts.push(chunk)
      this.total += chunk.length
    }
    const out = new Uint8Array(n)
    let filled = 0
    while (filled < n) {
      const part = this.parts[0]
      const take = Math.min(part.length - this.off, n - filled)
      out.set(part.subarray(this.off, this.off + take), filled)
      filled += take
      this.off += take
      this.total -= take
      if (this.off >= part.length) {
        this.parts.shift()
        this.off = 0
      }
    }
    return out
  }
}

function hs(type, body) {
  const out = new Uint8Array(4 + body.length)
  out[0] = type
  out[1] = (body.length >> 16) & 255
  out[2] = (body.length >> 8) & 255
  out[3] = body.length & 255
  out.set(body, 4)
  return out
}

function ext(type, data) {
  return concat(u16(type), u16(data.length), data)
}

function seqBytes(n) {
  const out = new Uint8Array(8)
  let v = n
  for (let i = 7; i >= 0; i--) {
    out[i] = Number(v & 0xffn)
    v >>= 8n
  }
  return out
}

async function hmac(keyBytes, data) {
  const key = await crypto.subtle.importKey('raw', keyBytes, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'])
  return new Uint8Array(await crypto.subtle.sign('HMAC', key, data))
}

async function prf(secret, label, seed, length) {
  const labeled = concat(utf8(label), seed)
  let result = new Uint8Array(0)
  let a = labeled
  while (result.length < length) {
    a = await hmac(secret, a)
    result = concat(result, await hmac(secret, concat(a, labeled)))
  }
  return result.subarray(0, length)
}

async function sign(privateKey, data) {
  const raw = new Uint8Array(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, privateKey, data))
  return p1363ToDer(raw)
}

async function verify(publicKey, data, der) {
  return crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, publicKey, derToP1363(der), data)
}

async function ephemeral() {
  const pair = await crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, false, ['deriveBits'])
  const point = new Uint8Array(await crypto.subtle.exportKey('raw', pair.publicKey))
  if (point.length !== 65 || point[0] !== 4) throw new TlsError('Could not start the secure connection.')
  return { privateKey: pair.privateKey, point }
}

async function sharedSecret(privateKey, point) {
  const pub = await crypto.subtle.importKey('raw', point, { name: 'ECDH', namedCurve: 'P-256' }, false, [])
  return new Uint8Array(await crypto.subtle.deriveBits({ name: 'ECDH', public: pub }, privateKey, 256))
}

async function importAes(raw, usages) {
  return crypto.subtle.importKey('raw', raw, { name: 'AES-GCM' }, false, usages)
}

function aad(seq, type, version, length) {
  return concat(seqBytes(seq), Uint8Array.of(type, version[0], version[1], (length >> 8) & 255, length & 255))
}

function clientHello(serverName, random, sessionId) {
  const name = utf8(serverName)
  const host = concat(Uint8Array.of(0), u16(name.length), name)
  const extensions = concat(
    ext(0, concat(u16(host.length), host)),
    ext(0x0017, new Uint8Array(0)),
    ext(0xff01, Uint8Array.of(0)),
    ext(0x000a, concat(u16(2), Uint8Array.of(0x00, 0x17))),
    ext(0x000b, Uint8Array.of(1, 0)),
    ext(0x000d, concat(u16(2), Uint8Array.of(0x04, 0x03)))
  )
  const body = concat(
    Uint8Array.of(0x03, 0x03),
    random,
    Uint8Array.of(sessionId.length),
    sessionId,
    Uint8Array.of(0x00, 0x02, 0xc0, 0x2b),
    Uint8Array.of(0x01, 0x00),
    u16(extensions.length),
    extensions
  )
  return hs(HS.ClientHello, body)
}

function parseExtensions(bytes, offset) {
  const found = new Map()
  if (offset >= bytes.length) return found
  const total = readU16(bytes, offset)
  let p = offset + 2
  const end = p + total
  if (end > bytes.length) throw new TlsError('The secure connection failed.')
  while (p + 4 <= end) {
    const type = readU16(bytes, p)
    const len = readU16(bytes, p + 2)
    p += 4
    found.set(type, bytes.subarray(p, p + len))
    p += len
  }
  return found
}

function parseServerHello(msg) {
  const body = msg.subarray(4)
  if (body.length < 34 || body[0] !== 3 || body[1] !== 3) throw new TlsError('The other device did not offer TLS 1.2.', 70)
  const random = body.subarray(2, 34)
  let p = 34
  const sidLen = body[p++]
  p += sidLen
  const cipher = readU16(body, p)
  p += 2
  p += 1
  if (cipher !== TLS_AES) throw new TlsError('The other device did not offer the FlipSend cipher.', 40)
  const extensions = parseExtensions(body, p)
  return { random, ems: extensions.has(0x0017) }
}

function parseCertificateList(msg) {
  const body = msg.subarray(4)
  const total = readU24(body, 0)
  if (total < 3) throw new TlsError('Missing peer certificate.', 42)
  const len = readU24(body, 3)
  return body.subarray(6, 6 + len)
}

function parseServerKeyExchange(msg) {
  const body = msg.subarray(4)
  if (body[0] !== 3) throw new TlsError('The secure connection failed.', 40)
  const curve = readU16(body, 1)
  if (curve !== 0x0017) throw new TlsError('The secure connection failed.', 40)
  const pointLen = body[3]
  const point = body.subarray(4, 4 + pointLen)
  let p = 4 + pointLen
  const sigAlg = readU16(body, p)
  p += 2
  const sigLen = readU16(body, p)
  p += 2
  const signature = body.subarray(p, p + sigLen)
  if (sigAlg !== 0x0403) throw new TlsError('The secure connection failed.', 40)
  return { params: body.subarray(0, 4 + pointLen), point, signature }
}

export class TlsConnection {
  constructor(transport, identity) {
    this.transport = transport
    this.identity = identity
    this.queue = new Queue(() => transport.read())
    this.transcript = new Uint8Array(0)
    this.sessionHash = null
    this.ems = false
    this.clientRandom = null
    this.serverRandom = null
    this.readEncrypted = false
    this.writeEncrypted = false
    this.readSeq = 0n
    this.writeSeq = 0n
    this.app = new Uint8Array(0)
    this.appOff = 0
    this.eof = false
    this.remoteFingerprint = null
    this.remoteSpki = null
  }

  async clientHandshake({ expectedFingerprint, allowNew, serverName = 'stellarsend' }) {
    this.clientRandom = randomBytes(32)
    const hello = clientHello(serverName, this.clientRandom, randomBytes(32))
    this.transcript = hello
    await this.writeRecord(22, hello)
    const flight = await this.readHandshakeUntil(HS.ServerHelloDone)
    let cert = null
    let ske = null
    let asked = false
    for (const msg of flight) {
      if (msg[0] === HS.ServerHello) {
        const parsed = parseServerHello(msg)
        this.serverRandom = parsed.random
        this.ems = parsed.ems
      } else if (msg[0] === HS.Certificate) {
        cert = parseCertificateList(msg)
      } else if (msg[0] === HS.ServerKeyExchange) {
        ske = parseServerKeyExchange(msg)
      } else if (msg[0] === HS.CertificateRequest) {
        asked = true
      }
    }
    if (!cert || !ske || !asked) throw new TlsError('The secure connection did not request a paired certificate.', 40)
    await this.pin(cert, expectedFingerprint, allowNew)
    const ok = await verify(this.remoteKey, concat(this.clientRandom, this.serverRandom, ske.params), ske.signature)
    if (!ok) throw new TlsError('The other device key did not verify.', 51)
    const mine = await ephemeral()
    const premaster = await sharedSecret(mine.privateKey, ske.point)
    const certificate = certificateMessage(this.identity.certDer)
    const exchange = hs(HS.ClientKeyExchange, concat(Uint8Array.of(mine.point.length), mine.point))
    this.transcript = concat(this.transcript, certificate, exchange)
    this.sessionHash = sha256(this.transcript)
    await this.installKeys(premaster)
    const verifyMsg = hs(HS.CertificateVerify, await digitallySigned(this.identity.privateKey, this.transcript))
    this.transcript = concat(this.transcript, verifyMsg)
    await this.writeRecord(22, concat(certificate, exchange, verifyMsg))
    await this.writeRecord(20, Uint8Array.of(1))
    this.writeEncrypted = true
    this.writeSeq = 0n
    await this.sendFinished('client finished')
    await this.expectChangeCipher()
    this.readEncrypted = true
    this.readSeq = 0n
    await this.expectFinished('server finished')
  }

  async serverHandshake({ expectedFingerprint, allowNew }) {
    const helloFlight = await this.readHandshakeUntil(HS.ClientHello)
    const hello = helloFlight[0]
    const parsed = parseClientHello(hello)
    this.clientRandom = parsed.random
    this.ems = parsed.ems
    if (!parsed.suites.includes(TLS_AES)) throw new TlsError('The other device did not offer the FlipSend cipher.', 40)
    this.serverRandom = randomBytes(32)
    const mine = await ephemeral()
    const serverHello = hs(HS.ServerHello, serverHelloBody(this.serverRandom, parsed.sessionId, parsed))
    const certificate = certificateMessage(this.identity.certDer)
    const params = concat(Uint8Array.of(3, 0x00, 0x17, mine.point.length), mine.point)
    const signed = await digitallySigned(this.identity.privateKey, concat(this.clientRandom, this.serverRandom, params))
    const ske = hs(HS.ServerKeyExchange, concat(params, signed))
    const request = hs(HS.CertificateRequest, concat(
      Uint8Array.of(1, 64),
      u16(2),
      Uint8Array.of(0x04, 0x03),
      u16(0)
    ))
    const done = hs(HS.ServerHelloDone, new Uint8Array(0))
    const flight = concat(serverHello, certificate, ske, request, done)
    this.transcript = concat(this.transcript, flight)
    await this.writeRecord(22, flight)
    const client = await this.readHandshakeUntil(HS.CertificateVerify)
    let cert = null
    let point = null
    let signature = null
    for (const msg of client) {
      if (msg[0] === HS.Certificate) cert = parseCertificateList(msg)
      else if (msg[0] === HS.ClientKeyExchange) {
        const body = msg.subarray(4)
        point = body.subarray(1, 1 + body[0])
      } else if (msg[0] === HS.CertificateVerify) {
        signature = parseDigitallySigned(msg)
      }
    }
    if (!cert || !point || !signature) throw new TlsError('Missing peer certificate.', 42)
    await this.pin(cert, expectedFingerprint, allowNew)
    const withoutVerify = this.transcript.subarray(0, this.transcript.length - client[client.length - 1].length)
    const ok = await verify(this.remoteKey, withoutVerify, signature)
    if (!ok) throw new TlsError('The other device key did not verify.', 51)
    if (!this.sessionHash) throw new TlsError('The secure connection failed.')
    const premaster = await sharedSecret(mine.privateKey, point)
    await this.installKeys(premaster)
    await this.expectChangeCipher()
    this.readEncrypted = true
    this.readSeq = 0n
    await this.expectFinished('client finished')
    await this.writeRecord(20, Uint8Array.of(1))
    this.writeEncrypted = true
    this.writeSeq = 0n
    await this.sendFinished('server finished')
  }

  async pin(certDer, expectedFingerprint, allowNew) {
    const parsed = await publicKeyFromCert(certDer)
    if (expectedFingerprint && parsed.fingerprint !== expectedFingerprint.toLowerCase()) {
      throw new TlsError('The paired device key changed.', 42)
    }
    if (!expectedFingerprint && !allowNew) throw new TlsError('Unknown peer.', 42)
    this.remoteFingerprint = parsed.fingerprint
    this.remoteSpki = parsed.spki
    this.remoteKey = parsed.key
  }

  async installKeys(premaster) {
    const seed = this.ems ? this.sessionHash : concat(this.clientRandom, this.serverRandom)
    const label = this.ems ? 'extended master secret' : 'master secret'
    if (!seed) throw new TlsError('The secure connection failed.')
    const master = await prf(premaster, label, seed, 48)
    const block = await prf(master, 'key expansion', concat(this.serverRandom, this.clientRandom), 40)
    this.master = master
    const clientKey = await importAes(block.subarray(0, 16), ['encrypt', 'decrypt'])
    const serverKey = await importAes(block.subarray(16, 32), ['encrypt', 'decrypt'])
    const clientIv = block.subarray(32, 36)
    const serverIv = block.subarray(36, 40)
    const client = this.role === 'client'
    this.writeKey = client ? clientKey : serverKey
    this.readKey = client ? serverKey : clientKey
    this.writeIv = client ? clientIv : serverIv
    this.readIv = client ? serverIv : clientIv
  }

  async sendFinished(label) {
    const verifyData = await prf(this.master, label, sha256(this.transcript), 12)
    const message = hs(HS.Finished, verifyData)
    this.transcript = concat(this.transcript, message)
    await this.writeRecord(22, message)
  }

  async expectChangeCipher() {
    const rec = await this.readRecord()
    if (rec.type === 21) this.failAlert(rec.fragment)
    if (rec.type !== 20 || rec.fragment.length !== 1 || rec.fragment[0] !== 1) {
      throw new TlsError('The secure connection failed.', 10)
    }
  }

  async expectFinished(label) {
    const messages = await this.readHandshakeUntil(HS.Finished)
    const message = messages[messages.length - 1]
    const body = message.subarray(4)
    const transcriptBefore = this.transcript.subarray(0, this.transcript.length - message.length)
    const expect = await prf(this.master, label, sha256(transcriptBefore), 12)
    if (!equalBytes(body, expect)) throw new TlsError('The secure connection failed.', 51)
  }

  async readHandshakeUntil(stop) {
    const messages = []
    let pending = this.spill || new Uint8Array(0)
    this.spill = new Uint8Array(0)
    while (true) {
      while (pending.length >= 4) {
        const len = readU24(pending, 1)
        if (pending.length < 4 + len) break
        const msg = pending.subarray(0, 4 + len)
        pending = pending.subarray(4 + len)
        this.transcript = concat(this.transcript, msg)
        if (msg[0] === HS.ClientKeyExchange) this.sessionHash = sha256(this.transcript)
        messages.push(msg)
        if (msg[0] === stop) {
          this.spill = pending
          return messages
        }
      }
      const rec = await this.readRecord()
      if (rec.type === 21) this.failAlert(rec.fragment)
      if (rec.type !== 22) throw new TlsError('The secure connection failed.', 10)
      pending = concat(pending, rec.fragment)
    }
  }

  async readRecord() {
    const header = await this.queue.pull(5)
    const type = header[0]
    const version = header.subarray(1, 3)
    const length = readU16(header, 3)
    if (length > 18432) throw new TlsError('The secure connection failed.', 22)
    const fragment = await this.queue.pull(length)
    if (!this.readEncrypted) return { type, version, fragment }
    if (fragment.length < 24) throw new TlsError('The secure connection failed.', 20)
    const explicit = fragment.subarray(0, 8)
    const sealed = fragment.subarray(8)
    const plainLen = sealed.length - 16
    const iv = concat(this.readIv, explicit)
    let plain
    try {
      plain = new Uint8Array(await crypto.subtle.decrypt({
        name: 'AES-GCM',
        iv,
        additionalData: aad(this.readSeq, type, version, plainLen),
        tagLength: 128
      }, this.readKey, sealed))
    } catch {
      throw new TlsError('The secure connection failed.', 20)
    }
    this.readSeq++
    return { type, version, fragment: plain }
  }

  async writeRecord(type, fragment) {
    const version = Uint8Array.of(0x03, 0x03)
    let payload = fragment
    if (this.writeEncrypted) {
      const explicit = seqBytes(this.writeSeq)
      const iv = concat(this.writeIv, explicit)
      const sealed = new Uint8Array(await crypto.subtle.encrypt({
        name: 'AES-GCM',
        iv,
        additionalData: aad(this.writeSeq, type, version, fragment.length),
        tagLength: 128
      }, this.writeKey, fragment))
      payload = concat(explicit, sealed)
      this.writeSeq++
    }
    const header = concat(Uint8Array.of(type), version, u16(payload.length))
    await this.transport.write(concat(header, payload))
  }

  failAlert(body) {
    const desc = body[1]
    if (desc === 0) {
      this.eof = true
      const error = new Error('Connection closed.')
      error.code = 'eof'
      throw error
    }
    const name = ALERTS[desc] || 'alert'
    throw new TlsError(`The secure connection failed (${name}).`, desc)
  }

  async readExactly(n) {
    while (this.app.length - this.appOff < n) {
      if (this.eof) {
        const error = new Error('Connection closed.')
        error.code = 'eof'
        throw error
      }
      const rec = await this.readRecord()
      if (rec.type === 21) {
        try {
          this.failAlert(rec.fragment)
        } catch (error) {
          if (error.code === 'eof') {
            this.eof = true
            continue
          }
          throw error
        }
      } else if (rec.type === 22) {
        continue
      } else if (rec.type !== 23) {
        throw new TlsError('The secure connection failed.', 10)
      } else if (rec.fragment.length) {
        const rest = this.app.subarray(this.appOff)
        this.app = concat(rest, rec.fragment)
        this.appOff = 0
      }
    }
    const out = this.app.subarray(this.appOff, this.appOff + n)
    this.appOff += n
    return out
  }

  async write(bytes) {
    let off = 0
    while (off < bytes.length) {
      const n = Math.min(16384, bytes.length - off)
      await this.writeRecord(23, bytes.subarray(off, off + n))
      off += n
    }
  }

  close() {
    this.transport.close?.()
  }
}

function certificateMessage(certDer) {
  return hs(HS.Certificate, concat(u24(certDer.length + 3), u24(certDer.length), certDer))
}

async function digitallySigned(privateKey, data) {
  const signature = await sign(privateKey, data)
  return concat(Uint8Array.of(0x04, 0x03), u16(signature.length), signature)
}

function parseDigitallySigned(msg) {
  const body = msg.subarray(4)
  const len = readU16(body, 2)
  return body.subarray(4, 4 + len)
}

function parseClientHello(msg) {
  const body = msg.subarray(4)
  if (body[0] !== 3) throw new TlsError('The other device did not offer TLS 1.2.', 70)
  const random = body.subarray(2, 34)
  let p = 34
  const sidLen = body[p++]
  const sessionId = body.subarray(p, p + sidLen)
  p += sidLen
  const suiteLen = readU16(body, p)
  p += 2
  const suites = []
  for (let i = 0; i < suiteLen; i += 2) suites.push(readU16(body, p + i))
  p += suiteLen
  const compLen = body[p++]
  p += compLen
  const extensions = parseExtensions(body, p)
  const groups = extensions.get(0x000a)
  if (groups && !containsU16(groups.subarray(2), 0x0017)) throw new TlsError('The secure connection failed.', 40)
  return {
    random,
    sessionId,
    suites,
    ems: extensions.has(0x0017),
    renegotiation: extensions.has(0xff01) || suites.includes(0x00ff)
  }
}

function containsU16(bytes, value) {
  for (let i = 0; i + 1 < bytes.length; i += 2) if (readU16(bytes, i) === value) return true
  return false
}

function serverHelloBody(random, sessionId, flags) {
  const parts = []
  if (flags.renegotiation) parts.push(ext(0xff01, Uint8Array.of(0)))
  if (flags.ems) parts.push(ext(0x0017, new Uint8Array(0)))
  const extensions = parts.length ? concat(...parts) : new Uint8Array(0)
  return concat(
    Uint8Array.of(0x03, 0x03),
    random,
    Uint8Array.of(sessionId.length),
    sessionId,
    Uint8Array.of(0xc0, 0x2b, 0x00),
    u16(extensions.length),
    extensions
  )
}

export async function connectTls(transport, identity, options) {
  const conn = new TlsConnection(transport, identity)
  conn.role = 'client'
  await conn.clientHandshake(options)
  return conn
}

export async function acceptTls(transport, identity, options) {
  const conn = new TlsConnection(transport, identity)
  conn.role = 'server'
  await conn.serverHandshake(options)
  return conn
}
