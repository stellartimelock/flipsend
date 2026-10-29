import { b64url, concat, utf8, utf8Decode } from './bytes.js'
import { Sha256 } from './sha256.js'
import { openStore } from './files.js'
import {
  FAIR_USE_BYTES,
  MAX_CHUNK,
  MAX_FILE,
  MAX_TEXT,
  Message,
  PACKAGE_NAME,
  ProtocolError,
  QUOTA_MESSAGE,
  QuotaError,
  TCP_PORT,
  fairUsePause,
  helloBody,
  jsonBytes,
  overCap,
  pairedRoom,
  pairingRoom,
  pairingToQr,
  parseJson,
  parsePairing,
  contentType,
  sanitizeName,
  usageLabel
} from './protocol.js'
import { encodeChunk, decodeChunk, encodeFrame, decodeFrame } from './protocol.js'
import { openRelay, probeUsage } from './relay.js'
import { acceptTls, connectTls } from './tls.js'
import { idbDelete, idbGet, idbSet } from './vault.js'

const HANDSHAKE_MS = 15000
const ACCEPT_MS = 3 * 60 * 1000
const CHUNK_MS = 60000
const PAIRING_MS = 2 * 60 * 1000

export function exportQr(identity) {
  return pairingToQr({
    version: 1,
    deviceId: identity.deviceId,
    pk: b64url(identity.spki),
    fp: identity.fingerprint,
    host: '',
    port: TCP_PORT,
    name: identity.name,
    ips: []
  })
}

export function usageLine(meter) {
  if (!meter) return ''
  return usageLabel(meter.used, meter.limit)
}

export function createClient({ identity, getPeer, setPeer, relayUrl, hooks }) {
  let generation = 0
  let listenAbort = null
  let active = null
  let busy = false
  let pairingUntil = 0
  let windowTimer = 0
  let wakeLock = null
  let lastJob = null
  const storePromise = openStore()

  function peer() {
    return getPeer() || null
  }

  function credential(current = peer()) {
    if (!current?.quotaKey) return null
    return { packageName: PACKAGE_NAME, entitlementKey: current.quotaKey }
  }

  async function remember(hello, spki) {
    const current = peer()
    const next = {
      deviceId: String(hello.deviceId).toLowerCase(),
      name: hello.name,
      fingerprint: String(hello.fingerprint).toLowerCase(),
      pk: spki ? b64url(spki) : current?.pk || '',
      quotaKey: hello.quotaKey || current?.quotaKey || null,
      plan: hello.plan || current?.plan || null,
      relayUsed: hello.relayUsed ?? current?.relayUsed ?? null,
      relayLimit: hello.relayLimit ?? current?.relayLimit ?? null,
      pairedAt: current?.deviceId === String(hello.deviceId).toLowerCase() ? current.pairedAt : Date.now()
    }
    await setPeer(next)
    hooks.paired?.(next)
    return next
  }

  function status(text) {
    hooks.status?.(text)
  }

  async function holdWake() {
    if (typeof navigator === 'undefined' || !navigator.wakeLock?.request) return
    try {
      wakeLock = await navigator.wakeLock.request('screen')
    } catch {
      wakeLock = null
    }
  }

  function releaseWake() {
    const held = wakeLock
    wakeLock = null
    held?.release?.().catch(() => {})
  }

  function stopListen() {
    generation += 1
    listenAbort?.abort()
    listenAbort = null
    try { active?.close() } catch { /* already closed */ }
    active = null
  }

  async function checkCap() {
    const cred = credential()
    if (!cred) return
    try {
      const meter = await probeUsage(relayUrl(), cred)
      hooks.meter?.(meter)
      if (overCap(meter)) {
        const message = meter.limit >= FAIR_USE_BYTES ? fairUsePause(meter.limit) : QUOTA_MESSAGE
        throw new QuotaError(message)
      }
    } catch (error) {
      if (error instanceof QuotaError) throw error
    }
  }

  async function listenLoop(gen, signal) {
    const store = await storePromise
    while (gen === generation && !signal.aborted) {
      const current = peer()
      const windowOpen = Date.now() < pairingUntil
      const room = windowOpen
        ? pairingRoom(identity.deviceId)
        : current ? pairedRoom(identity.deviceId, current.deviceId) : null
      if (!room) {
        status(current ? 'Ready' : '')
        await sleep(500, signal)
        continue
      }
      let opened = null
      try {
        status(windowOpen ? 'Waiting for a device' : 'Ready')
        opened = await openRelay({
          url: relayUrl(),
          room,
          role: 'listen',
          credential: credential(current),
          signal,
          onWake: () => status('Waking relay…')
        })
        if (gen !== generation || signal.aborted) {
          opened.stream.close()
          return
        }
        hooks.meter?.(opened.meter)
        status('Through relay')
        const tls = await acceptTls(opened.stream, identity, {
          expectedFingerprint: windowOpen ? null : current?.fingerprint || null,
          allowNew: windowOpen
        })
        active = tls
        await holdWake()
        const before = peer()?.deviceId || null
        await receiveOn(tls, {
          identity,
          expected: windowOpen ? null : current,
          allowNew: windowOpen,
          store,
          confirmPair: async (hello) => {
            const ok = await hooks.confirmPair({ name: hello.name, fingerprint: hello.fingerprint })
            if (ok) await remember(hello, tls.remoteSpki)
            return ok
          },
          confirmOffer: (offer) => hooks.confirmOffer(offerView(offer)),
          onHello: async (hello) => {
            if (!windowOpen) await remember(hello, tls.remoteSpki)
          },
          onProgress: (progress) => hooks.progress?.(progress),
          onReceived: (item) => hooks.received?.(item)
        })
        if (windowOpen && peer()?.deviceId && peer().deviceId !== before) {
          pairingUntil = 0
          clearTimeout(windowTimer)
        }
      } catch (error) {
        if (signal.aborted || gen !== generation) return
        opened?.stream?.close?.()
        if (error instanceof QuotaError) {
          status(error.message)
          hooks.meter?.({ enforced: true, used: FAIR_USE_BYTES, limit: FAIR_USE_BYTES })
          await sleep(30000, signal)
          continue
        }
        const reason = error?.code === 'eof'
          ? 'Disconnected. The other device hung up.'
          : (error.message || 'Disconnected. FlipSend will try again.')
        status(reason)
        hooks.disconnected?.(reason)
        await sleep(1000, signal)
      } finally {
        releaseWake()
        active = null
      }
    }
  }

  function start() {
    stopListen()
    const gen = generation
    const controller = new AbortController()
    listenAbort = controller
    listenLoop(gen, controller.signal).catch(() => {})
  }

  function showPairing() {
    pairingUntil = Date.now() + PAIRING_MS
    clearTimeout(windowTimer)
    windowTimer = setTimeout(() => {
      pairingUntil = 0
      if (!busy) start()
    }, PAIRING_MS)
    start()
    return exportQr(identity)
  }

  async function pairFromQr(text) {
    const advert = parsePairing(text)
    stopListen()
    busy = true
    let opened = null
    try {
      await checkCap()
      status('Waking relay…')
      opened = await openRelay({
        url: relayUrl(),
        room: pairingRoom(advert.deviceId),
        role: 'dial',
        credential: credential(),
        onWake: () => status('Waking relay…')
      })
      hooks.meter?.(opened.meter)
      const tls = await connectTls(opened.stream, identity, {
        expectedFingerprint: advert.fp,
        allowNew: false
      })
      active = tls
      await holdWake()
      const hello = await handshake(tls, {
        identity,
        expectedFingerprint: advert.fp,
        expectedDeviceId: advert.deviceId,
        allowNew: false
      })
      if (hello.fingerprint !== tls.remoteFingerprint) {
        throw new ProtocolError('Certificate does not match the hello message.')
      }
      await remember(hello, tls.remoteSpki)
      status('Paired with ' + hello.name)
      try { tls.close() } catch { /* paired */ }
    } finally {
      releaseWake()
      active = null
      busy = false
      start()
    }
  }

  async function sendFiles(files) {
    const list = [...files]
    if (!list.length) return
    lastJob = { kind: 'files', files: list }
    await runSend(async () => {
      const count = list.length
      for (let i = 0; i < list.length; i++) {
        const file = list[i]
        if (file.size > MAX_FILE) throw new ProtocolError('Content exceeds the size limit.', 'limit')
        status('Preparing ' + file.name)
        const sha = await hashStream(file.stream())
        await deliver(peer(), {
          kind: 'file',
          name: sanitizeName(file.name),
          mime: cleanMime(contentType(file.name, file.type)),
          size: file.size,
          sha256: sha,
          index: count > 1 ? i + 1 : 0,
          count,
          async readAt(offset, length) {
            const slice = file.slice(offset, offset + length)
            return new Uint8Array(await slice.arrayBuffer())
          }
        }, { name: file.name, index: count > 1 ? i + 1 : 0, count })
      }
    })
  }

  async function sendText(text) {
    const bytes = utf8(text)
    if (bytes.length > MAX_TEXT) throw new ProtocolError('Text is too long.', 'limit')
    lastJob = { kind: 'text', text }
    await runSend(async () => {
      await deliver(peer(), {
        kind: 'text',
        name: 'message.txt',
        mime: 'text/plain',
        size: bytes.length,
        sha256: new Sha256().update(bytes).hex(),
        index: 0,
        count: 1,
        async readAt(offset, length) {
          return bytes.subarray(offset, offset + length)
        }
      }, { name: 'message.txt', index: 0, count: 1 })
    })
  }

  async function retry() {
    if (!lastJob) throw new Error('Choose a file to send again.')
    if (lastJob.kind === 'text') return sendText(lastJob.text)
    return sendFiles(lastJob.files)
  }

  async function runSend(work) {
    if (!peer()) throw new Error('Pair a device first.')
    stopListen()
    busy = true
    try {
      await checkCap()
      await holdWake()
      await work()
      status('Sent.')
      await hooks.sessionDone?.()
    } finally {
      releaseWake()
      busy = false
      if (peer()) start()
    }
  }

  async function deliver(current, payload, label) {
    let last = null
    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        await once(current, payload, label)
        return
      } catch (error) {
        last = error
        if (fatal(error) || attempt === 1) throw error
        status('Reconnecting…')
      }
    }
    throw last
  }

  async function once(current, payload, label) {
    const opened = await openRelay({
      url: relayUrl(),
      room: pairedRoom(identity.deviceId, current.deviceId),
      role: 'dial',
      credential: credential(current),
      onWake: () => status('Waking relay…')
    })
    hooks.meter?.(opened.meter)
    const tls = await connectTls(opened.stream, identity, {
      expectedFingerprint: current.fingerprint,
      allowNew: false
    })
    active = tls
    try {
      await sendOn(tls, {
        identity,
        peer: current,
        payload,
        onHello: (hello) => remember(hello, tls.remoteSpki),
        onProgress: (progress) => hooks.progress?.({ ...progress, index: label.index, count: label.count })
      })
    } finally {
      try { tls.close() } catch { /* closed */ }
      active = null
    }
  }

  async function unpair() {
    pairingUntil = 0
    clearTimeout(windowTimer)
    stopListen()
    await setPeer(null)
    status('')
  }

  function nudge() {
    if (busy) {
      holdWake()
      return
    }
    start()
  }

  return { start, showPairing, pairFromQr, sendFiles, sendText, retry, unpair, nudge, exportQr: () => exportQr(identity) }
}

export async function handshake(conn, options) {
  const { identity, expectedFingerprint, expectedDeviceId, allowNew, confirmPair } = options
  await writeJson(conn, Message.Hello, helloBody(identity))
  const remoteFrame = await readFrame(conn, HANDSHAKE_MS)
  if (remoteFrame.type === Message.Error) throw errorOf(remoteFrame)
  if (remoteFrame.type !== Message.Hello) throw new ProtocolError('Expected a hello message.')
  const remote = parseJson(remoteFrame.payload)
  assertHello(remote)
  if (remote.fingerprint !== conn.remoteFingerprint) {
    await writeError(conn, null, 'bad_request', 'Certificate does not match the hello message.')
    throw new ProtocolError('Certificate does not match the hello message.')
  }
  const known = !!expectedFingerprint
  if (known && remote.fingerprint !== expectedFingerprint.toLowerCase()) {
    await writeError(conn, null, 'key_changed', 'The paired device key changed.')
    throw new ProtocolError('The paired device key changed.', 'key_changed')
  }
  if (known && expectedDeviceId && remote.deviceId.toLowerCase() !== expectedDeviceId.toLowerCase()) {
    await writeError(conn, null, 'key_changed', 'The paired device key changed.')
    throw new ProtocolError('The paired device key changed.', 'key_changed')
  }
  if (!known) {
    if (!allowNew || !confirmPair) {
      await writeError(conn, null, 'unknown_peer', 'This device is not paired.')
      throw new ProtocolError('This device is not paired.', 'unknown_peer')
    }
    const accepted = await confirmPair(remote)
    if (!accepted) {
      await writeError(conn, null, 'declined', 'Pairing was declined.')
      throw new ProtocolError('Pairing was declined.', 'declined')
    }
  }
  await writeJson(conn, Message.HelloAck, { deviceId: identity.deviceId, fingerprint: identity.fingerprint })
  const ackFrame = await readFrame(conn, HANDSHAKE_MS)
  if (ackFrame.type === Message.Error) throw errorOf(ackFrame)
  if (ackFrame.type !== Message.HelloAck) throw new ProtocolError('Expected a hello acknowledgement.')
  const ack = parseJson(ackFrame.payload)
  if (String(ack.deviceId).toLowerCase() !== remote.deviceId.toLowerCase() || ack.fingerprint !== remote.fingerprint) {
    throw new ProtocolError('Hello acknowledgement did not match.')
  }
  return remote
}

export async function sendOn(conn, options) {
  const { identity, peer, payload, onHello, onProgress } = options
  const transferId = crypto.randomUUID()
  try {
    const remote = await handshake(conn, {
      identity,
      expectedFingerprint: peer.fingerprint,
      expectedDeviceId: peer.deviceId,
      allowNew: false
    })
    await onHello?.(remote)
    const offer = offerBody(transferId, payload)
    onProgress?.({ name: payload.name, state: 'awaiting', done: 0, total: payload.size })
    await writeJson(conn, Message.Offer, offer)
    const reply = await readFrame(conn, ACCEPT_MS)
    if (reply.type === Message.Reject) {
      const reject = parseJson(reply.payload)
      throw new ProtocolError(reject.reason || 'Declined', 'declined')
    }
    if (reply.type === Message.Error) throw errorOf(reply)
    if (reply.type !== Message.Accept) throw new ProtocolError('The other device did not accept the transfer.')
    let sent = 0
    if (reply.payload.length) {
      const accept = parseJson(reply.payload)
      const resume = Number(accept.offset || 0)
      if (resume > payload.size) throw new ProtocolError('Could not resume the transfer.', 'io')
      sent = resume
    }
    while (sent < payload.size) {
      const length = Math.min(MAX_CHUNK, payload.size - sent)
      const data = await payload.readAt(sent, length)
      if (!data || data.length === 0) throw new ProtocolError('The file ended before the promised size.', 'io')
      const take = Math.min(data.length, length)
      await writeFrame(conn, Message.Chunk, encodeChunk(transferId, sent, data.subarray(0, take)))
      sent += take
      onProgress?.({ name: payload.name, state: 'sending', done: sent, total: payload.size })
    }
    await writeJson(conn, Message.Complete, { transferId, sha256: payload.sha256 })
    const done = await readFrame(conn, CHUNK_MS)
    if (done.type === Message.Error) throw errorOf(done)
    if (done.type !== Message.Complete) throw new ProtocolError('The other device did not confirm the transfer.')
    const complete = parseJson(done.payload)
    if (complete.sha256 !== payload.sha256) throw new ProtocolError('Integrity check failed.', 'hash_mismatch')
    onProgress?.({ name: payload.name, state: 'done', done: payload.size, total: payload.size })
  } catch (error) {
    if (error?.name === 'AbortError') {
      await writeJson(conn, Message.Cancel, { transferId }).catch(() => {})
    }
    throw error
  }
}

export async function receiveOn(conn, options) {
  const { identity, expected, allowNew, confirmPair, confirmOffer, onHello, onProgress, onReceived } = options
  const store = options.store || await openStore()
  const remote = await handshake(conn, {
    identity,
    expectedFingerprint: expected?.fingerprint || null,
    expectedDeviceId: expected?.deviceId || null,
    allowNew,
    confirmPair
  })
  await onHello?.(remote)
  let frame
  try {
    frame = await readFrame(conn, HANDSHAKE_MS)
  } catch (error) {
    if (error.code === 'eof') return { paired: true }
    throw error
  }
  if (frame.type === Message.Error) throw errorOf(frame)
  if (frame.type === Message.Service) {
    const request = parseJson(frame.payload)
    await writeJson(conn, Message.ServiceResult, {
      id: typeof request.id === 'string' ? request.id : '',
      ok: false,
      code: 'not_implemented',
      message: 'This browser does not accept companion calls.'
    })
    return { paired: false }
  }
  if (frame.type !== Message.Offer) throw new ProtocolError('Expected an offer.')
  const offer = parseJson(frame.payload)
  try {
    assertOffer(offer)
  } catch (error) {
    await writeJson(conn, Message.Reject, { transferId: offer.transferId || '', reason: error.message })
    throw error
  }
  onProgress?.({ name: offer.name, state: 'awaiting', done: 0, total: offer.size, index: offer.index || 0, count: offer.count || 0 })
  const accepted = await confirmOffer(offer)
  if (!accepted) {
    await writeJson(conn, Message.Reject, { transferId: offer.transferId, reason: 'Declined' })
    throw new ProtocolError('Declined', 'declined')
  }
  const fileName = sanitizeName(offer.name)
  const resume = offer.kind === 'file' ? await resumeState(store, offer.sha256) : { name: offer.sha256, offset: 0, hasher: new Sha256() }
  if (resume.offset > 0) await writeJson(conn, Message.Accept, { transferId: offer.transferId, offset: resume.offset })
  else await writeJson(conn, Message.Accept, { transferId: offer.transferId })
  let got = resume.offset
  const hasher = resume.hasher
  try {
    while (got < offer.size) {
      const incoming = await readFrame(conn, CHUNK_MS)
      if (incoming.type === Message.Cancel) {
        await store.drop(resume.name)
        throw new ProtocolError('Cancelled', 'cancelled')
      }
      if (incoming.type === Message.Error) throw errorOf(incoming)
      if (incoming.type !== Message.Chunk) throw new ProtocolError('Expected a chunk.')
      const chunk = decodeChunk(incoming.payload)
      if (chunk.transferId !== offer.transferId.toLowerCase() || chunk.offset !== BigInt(got)) {
        throw new ProtocolError('Chunk was out of order.')
      }
      if (got + chunk.data.length > offer.size) throw new ProtocolError('Chunk exceeds the offered size.', 'limit')
      hasher.update(chunk.data)
      await store.writeAt(resume.name, got, chunk.data)
      got += chunk.data.length
      await metaSet('resume:' + offer.sha256, { sha256: offer.sha256, hasher: hasher.exportState(), bytes: String(got) })
      onProgress?.({ name: fileName, state: 'receiving', done: got, total: offer.size, index: offer.index || 0, count: offer.count || 0 })
    }
    const end = await readFrame(conn, CHUNK_MS)
    if (end.type === Message.Cancel) {
      await store.drop(resume.name)
      throw new ProtocolError('Cancelled', 'cancelled')
    }
    if (end.type !== Message.Complete) throw new ProtocolError('Expected a completed transfer.')
    const complete = parseJson(end.payload)
    const actual = hasher.hex()
    if (actual !== offer.sha256 || actual !== complete.sha256) {
      await store.drop(resume.name)
      await metaDelete('resume:' + offer.sha256)
      await writeError(conn, offer.transferId, 'hash_mismatch', 'Integrity check failed.')
      throw new ProtocolError('Integrity check failed.', 'hash_mismatch')
    }
    const id = offer.transferId
    await store.commit(resume.name, id)
    await metaDelete('resume:' + offer.sha256)
    const blob = await store.file(id)
    const named = typeof File === 'function' ? new File([blob], fileName, { type: contentType(fileName, offer.mime) }) : blob
    let text = null
    if (offer.kind === 'text') text = utf8Decode(new Uint8Array(await blob.arrayBuffer()))
    await onReceived?.({ id, name: fileName, kind: offer.kind, mime: offer.mime, blob: named, text, sha256: actual })
    await writeJson(conn, Message.Complete, { transferId: offer.transferId, sha256: actual })
    onProgress?.({ name: fileName, state: 'done', done: offer.size, total: offer.size })
    return { paired: false, id, name: fileName }
  } catch (error) {
    if (error.code !== 'hash_mismatch' && error.code !== 'declined' && error.code !== 'cancelled') {
      await metaSet('resume:' + offer.sha256, { sha256: offer.sha256, hasher: hasher.exportState(), bytes: String(got) }).catch(() => {})
    }
    throw error
  }
}

function offerBody(transferId, payload) {
  const body = {
    transferId,
    kind: payload.kind,
    name: payload.name,
    size: payload.size,
    sha256: payload.sha256,
    mime: payload.mime
  }
  if (payload.count > 1 && payload.index > 0) {
    body.index = payload.index
    body.count = payload.count
  }
  return body
}

function offerView(offer) {
  return {
    name: offer.name,
    size: offer.size,
    kind: offer.kind,
    index: offer.index || 0,
    count: offer.count || 0
  }
}

function assertHello(hello) {
  if (hello.v !== 1) throw new ProtocolError('Unsupported hello version.')
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(hello.deviceId || '')) {
    throw new ProtocolError('Invalid device id.')
  }
  if (!hello.name || String(hello.name).length > 64) throw new ProtocolError('Invalid device name.')
  if (!/^[0-9a-f]{64}$/.test(hello.fingerprint || '')) throw new ProtocolError('Invalid fingerprint.')
}

function assertOffer(offer) {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(offer.transferId || '')) {
    throw new ProtocolError('Invalid transfer id.')
  }
  if (offer.kind !== 'file' && offer.kind !== 'text') throw new ProtocolError('Unsupported content kind.')
  if (typeof offer.size !== 'number' || offer.size < 0) throw new ProtocolError('Invalid content length.')
  const max = offer.kind === 'text' ? MAX_TEXT : MAX_FILE
  if (offer.size > max) throw new ProtocolError('Content exceeds the size limit.', 'limit')
  if (!offer.name || offer.name.length > 240) throw new ProtocolError('Name is too long.', 'limit')
  if (!offer.mime || offer.mime.length > 127 || /[\u0000-\u001f]/.test(offer.mime)) throw new ProtocolError('Invalid content type.')
  if (!/^[0-9a-f]{64}$/.test(offer.sha256 || '')) throw new ProtocolError('Invalid integrity hash.')
}

function errorOf(frame) {
  const body = parseJson(frame.payload)
  return new ProtocolError(body.message || 'The other device reported an error.', body.code || 'bad_request')
}

async function writeError(conn, transferId, code, message) {
  await writeJson(conn, Message.Error, { transferId, code, message })
}

async function writeJson(conn, type, value) {
  await writeFrame(conn, type, jsonBytes(value))
}

async function writeFrame(conn, type, payload) {
  await conn.write(encodeFrame(type, payload))
}

async function readFrame(conn, ms) {
  try {
    const header = await withTimeout(conn.readExactly(12), ms, conn)
    const length = new DataView(header.buffer, header.byteOffset, header.byteLength).getUint32(8)
    const rest = length ? await withTimeout(conn.readExactly(length), ms, conn) : new Uint8Array(0)
    return decodeFrame(concat(header, rest))
  } catch (error) {
    if (error?.code === 'eof' || error?.message === 'Connection closed.') {
      const eof = new ProtocolError('Connection closed.', 'eof')
      eof.code = 'eof'
      throw eof
    }
    throw error
  }
}

function withTimeout(work, ms, conn) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      try { conn.close() } catch { /* closed */ }
      reject(new ProtocolError('The other device did not answer in time.', 'io'))
    }, ms)
    work.then(
      (value) => { clearTimeout(timer); resolve(value) },
      (error) => { clearTimeout(timer); reject(error) }
    )
  })
}

async function resumeState(store, sha) {
  const size = await store.size(sha)
  const meta = await metaGet('resume:' + sha)
  if (meta && meta.sha256 === sha && Number(meta.bytes) === size && size > 0 && meta.hasher) {
    return { name: sha, offset: size, hasher: Sha256.importState(meta.hasher) }
  }
  if (size > 0) await store.drop(sha)
  return { name: sha, offset: 0, hasher: new Sha256() }
}

async function hashStream(stream) {
  const hasher = new Sha256()
  const reader = stream.getReader()
  while (true) {
    const step = await reader.read()
    if (step.done) break
    hasher.update(step.value)
  }
  return hasher.hex()
}

function cleanMime(mime) {
  const text = String(mime || 'application/octet-stream').replace(/[\u0000-\u001f]/g, '').slice(0, 127)
  return text || 'application/octet-stream'
}

function fatal(error) {
  if (error instanceof QuotaError) return true
  return ['declined', 'hash_mismatch', 'key_changed', 'unknown_peer', 'cancelled', 'limit'].includes(error?.code)
}

async function metaGet(key) {
  if (typeof indexedDB === 'undefined') return null
  try { return await idbGet(key) } catch { return null }
}

async function metaSet(key, value) {
  if (typeof indexedDB === 'undefined') return
  try { await idbSet(key, value) } catch { /* resume metadata is optional */ }
}

async function metaDelete(key) {
  if (typeof indexedDB === 'undefined') return
  try { await idbDelete(key) } catch { /* already gone */ }
}

function sleep(ms, signal) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(abortError())
      return
    }
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', onAbort)
      resolve()
    }, ms)
    const onAbort = () => {
      clearTimeout(timer)
      reject(abortError())
    }
    signal?.addEventListener('abort', onAbort, { once: true })
  })
}

function abortError() {
  const error = new Error('Cancelled')
  error.name = 'AbortError'
  return error
}
