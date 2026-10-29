import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { X509Certificate, createVerify } from 'node:crypto'
import fs from 'node:fs'
import net from 'node:net'
import os from 'node:os'
import path from 'node:path'
import { test } from 'node:test'
import tls from 'node:tls'
import { fileURLToPath } from 'node:url'
import { receiveOn, sendOn } from '../js/client.js'
import { createIdentity } from '../js/cert.js'
import { fingerprintOf, parseCertificate, publicKeyFromCert } from '../js/cert.js'
import { b64urlDecode, hex, unhex, utf8 } from '../js/bytes.js'
import {
  DEFAULT_RELAY,
  FAIR_USE_BYTES,
  decodeChunk,
  contentType,
  decodeFrame,
  encodeChunk,
  encodeFrame,
  entitlementKey,
  fairUsePause,
  formatGb,
  helloBody,
  joinMessage,
  jsonBytes,
  meterFrom,
  overCap,
  pairedRoom,
  pairingRoom,
  pairingToQr,
  parsePairing,
  relayUrl,
  sha256HexText,
  shortCode,
  terminalMessage,
  usageLabel,
  usageMessage
} from '../js/protocol.js'
import { sha256 } from '../js/sha256.js'
import { acceptTls, connectTls } from '../js/tls.js'
import { openVault, sealVault } from '../js/vault.js'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')

function pem(label, der) {
  const body = Buffer.from(der).toString('base64').match(/.{1,64}/g).join('\n')
  return `-----BEGIN ${label}-----\n${body}\n-----END ${label}-----\n`
}

function socketStream(socket) {
  const chunks = []
  let ended = false
  let waiter = null
  let failed = null
  socket.on('data', (buf) => {
    chunks.push(new Uint8Array(buf.buffer, buf.byteOffset, buf.byteLength))
    if (waiter) {
      const wake = waiter
      waiter = null
      wake()
    }
  })
  const finish = (error) => {
    ended = true
    failed = error || failed
    if (waiter) {
      const wake = waiter
      waiter = null
      wake()
    }
  }
  socket.on('end', () => finish())
  socket.on('close', () => finish())
  socket.on('error', (error) => finish(error))
  return {
    async read() {
      while (chunks.length === 0) {
        if (ended) {
          if (failed) throw failed
          return null
        }
        await new Promise((resolve) => { waiter = resolve })
      }
      return chunks.shift()
    },
    async write(bytes) {
      if (socket.destroyed) throw new Error('Connection closed.')
      await new Promise((resolve, reject) => {
        socket.write(Buffer.from(bytes), (error) => error ? reject(error) : resolve())
      })
    },
    close() {
      socket.destroy()
    }
  }
}

test('the browser page is a landing plus QR pairing, with no passphrase lock', () => {
  const html = fs.readFileSync(path.join(root, 'web/index.html'), 'utf8')
  const js = fs.readFileSync(path.join(root, 'web/js/app.js'), 'utf8')
  const sw = fs.readFileSync(path.join(root, 'web/sw.js'), 'utf8')
  assert.doesNotMatch(html, /passphrase/i)
  assert.doesNotMatch(html, /unlock/i)
  assert.doesNotMatch(html, /Scan a QR to connect/)
  assert.doesNotMatch(html, /Nothing is stored outside/)
  assert.doesNotMatch(html, /The page encrypts/)
  assert.doesNotMatch(html, /short code works/)
  assert.doesNotMatch(html, /Paste a code/)
  assert.doesNotMatch(html, /Nothing received yet/)
  assert.doesNotMatch(html, /Send files/)
  assert.match(html, /Connect with iPhone/)
  assert.match(html, /\$2\.9999/)
  assert.match(html, /Coming soon: Bot Link, relay plans at \$5, \$10, or \$15\/mo for always-on bot links\./)
  assert.match(html, /about 100 MB a month/)
  assert.match(html, /Wi-Fi transfers still work/)
  assert.doesNotMatch(html, /Optional relay plans/)
  assert.doesNotMatch(html, /Plus \$5\.99/)
  assert.doesNotMatch(html, /Max \$11\.99/)
  assert.match(html, /id="qr"/)
  assert.match(html, />Scan a code</)
  assert.match(html, /Type a code/)
  assert.match(html, /48 MB per file/)
  assert.match(html, /100 MB a month/)
  assert.match(html, /accept="image\/\*,video\/\*,\*\/\*"/)
  assert.match(html, />File</)
  assert.match(html, />Send</)
  assert.doesNotMatch(js, /passphrase/i)
  assert.doesNotMatch(js, /sealVault|openVault/)
  assert.match(js, /forgetOldLock/)
  assert.match(js, /endSession/)
  assert.match(sw, /flipsend-web-9/)
})

test('videos keep a real type so they show up as video files', () => {
  assert.equal(contentType('clip.mp4', ''), 'video/mp4')
  assert.equal(contentType('clip.MOV', 'application/octet-stream'), 'video/quicktime')
  assert.equal(contentType('photo.jpg', '*/*'), 'image/jpeg')
  assert.equal(contentType('file.bin', 'video/mp4'), 'video/mp4')
})

test('sha256 matches the published vector and WebCrypto', async () => {
  assert.equal(hex(sha256(utf8('abc'))), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')
  const data = utf8('flipsend-room-v1|left|right')
  const subtle = hex(new Uint8Array(await crypto.subtle.digest('SHA-256', data)))
  assert.equal(hex(sha256(data)), subtle)
})

test('frames, chunks, rooms, and the pairing fixture match the existing format', () => {
  const vectors = JSON.parse(fs.readFileSync(path.join(root, 'testdata/protocol-vectors.json'), 'utf8'))
  for (const frame of vectors.frames) {
    const payload = unhex(frame.payloadHex)
    assert.equal(hex(encodeFrame(frame.type, payload, frame.flags)), frame.frameHex)
    const decoded = decodeFrame(unhex(frame.frameHex))
    assert.equal(decoded.type, frame.type)
    assert.equal(hex(decoded.payload), frame.payloadHex)
  }
  const chunk = vectors.chunks[0]
  const encoded = encodeChunk(chunk.transferId, chunk.offset, unhex(chunk.dataHex))
  assert.equal(hex(encoded), chunk.payloadHex)
  const round = decodeChunk(encoded)
  assert.equal(round.transferId, chunk.transferId)
  assert.equal(round.offset, BigInt(chunk.offset))

  const hello = vectors.frames.find((frame) => frame.name === 'hello')
  assert.equal(hex(jsonBytes(helloBody({
    deviceId: '11111111-1111-1111-1111-111111111111',
    name: 'Desk',
    fingerprint: 'abababababababababababababababababababababababababababababababab'
  }))), hello.payloadHex)

  const pairing = JSON.parse(fs.readFileSync(path.join(root, 'testdata/pairing.json'), 'utf8'))
  const parsed = parsePairing(pairing.qr)
  assert.equal(parsed.deviceId, pairing.deviceId)
  assert.equal(parsed.fp, pairing.fp)
  assert.equal(hex(parsed.spki), pairing.spkiHex)
  assert.equal(fingerprintOf(parsed.spki), pairing.fp)
  assert.equal(fingerprintOf(b64urlDecode(pairing.pk)), pairing.fp)
  const again = pairingToQr({
    version: 1,
    deviceId: parsed.deviceId,
    pk: parsed.pk,
    fp: parsed.fp,
    host: parsed.host,
    port: parsed.port,
    name: parsed.name,
    ips: parsed.ips
  })
  assert.equal(again, pairing.qr)
  assert.equal(shortCode(pairing.fp), '84AB-4C24')

  assert.equal(pairedRoom('11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222'), '8d691f26b6b708eb4b0a058b4ab26c3550f9b2006086aba986f692df67957a74')
  assert.equal(pairingRoom('11111111-1111-1111-1111-111111111111'), '1b0b8e985e76c507f6a56dd97199f4a92ea67e8b78479e84df7e13d23e96e186')
  assert.equal(entitlementKey('11111111-1111-1111-1111-111111111111'), 'c30c56297025c9d0cdca357ebae9c3526f97f1155bf7938d10e243bd44bd6875')
  assert.equal(sha256HexText('stellarsend-quota-v1|11111111-1111-1111-1111-111111111111'), entitlementKey('11111111-1111-1111-1111-111111111111'))
})

test('fair-use labels, relay join, and quota match the apps', () => {
  assert.equal(usageLabel(20_000_000, 50_000_000_000), '20 MB of 100 MB relay this month')
  assert.equal(usageLabel(2_500_000_000, 50_000_000_000), '2.5 GB of 100 MB relay this month')
  assert.equal(formatGb(50_000_000_000), '50 GB')
  assert.equal(FAIR_USE_BYTES, 100_000_000)
  assert.match(fairUsePause(FAIR_USE_BYTES), /Wi-Fi transfers still work/)
  const room = pairedRoom('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb')
  assert.equal(joinMessage(room, 'listen'), `{"v":1,"room":"${room}","role":"listen"}`)
  const key = entitlementKey('11111111-1111-1111-1111-111111111111')
  const joined = joinMessage(room, 'dial', { packageName: 'org.stellartimelock.stellarsend', entitlementKey: key })
  assert.equal(JSON.parse(joined).entitlementKey, key)
  assert.equal(JSON.parse(usageMessage(null)).op, 'usage')
  assert.equal(relayUrl('https://relay.example/v1'), 'wss://relay.example/v1')
  assert.equal(relayUrl(DEFAULT_RELAY), DEFAULT_RELAY)
  const meter = meterFrom('{"v":1,"ok":true,"tier":"plus","used":50000000000,"limit":50000000000,"enforced":true,"resets":"2026-10"}')
  assert.equal(overCap(meter), true)
  assert.equal(meter.tier, 'plus')
})

test('a self-signed device certificate verifies in Node and keeps the SPKI pin', async () => {
  const identity = await createIdentity('Web')
  const parsed = parseCertificate(identity.certDer)
  assert.equal(hex(parsed.spki), hex(identity.spki))
  const checked = await publicKeyFromCert(identity.certDer)
  assert.equal(checked.fingerprint, identity.fingerprint)
  const x509 = new X509Certificate(pem('CERTIFICATE', identity.certDer))
  assert.equal(x509.verify(x509.publicKey), true)
  const spki = x509.publicKey.export({ type: 'spki', format: 'der' })
  assert.equal(hex(spki), hex(identity.spki))
  assert.equal(x509.subject.includes(identity.deviceId), true)
})

test('terminal signatures use the pairing key and the existing message', async () => {
  const identity = await createIdentity('Web')
  const id = '33333333-3333-3333-3333-333333333333'
  const command = 'hostname'
  const message = terminalMessage(id, command, false)
  const raw = new Uint8Array(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, identity.privateKey, message))
  const { derToP1363, p1363ToDer } = await import('../js/asn1.js')
  const der = p1363ToDer(raw)
  const verifier = createVerify('SHA256')
  verifier.update(Buffer.from(message))
  verifier.end()
  const nodeKey = pem('PUBLIC KEY', identity.spki)
  assert.equal(verifier.verify(nodeKey, Buffer.from(der)), true)
  assert.equal(derToP1363(der).length, 64)
})

test('the pairing vault is sealed only with the passphrase', async () => {
  const identity = await createIdentity('iPhone')
  const payload = {
    deviceId: identity.deviceId,
    name: identity.name,
    pkcs8: Buffer.from(identity.pkcs8).toString('base64url'),
    spki: Buffer.from(identity.spki).toString('base64url'),
    certDer: Buffer.from(identity.certDer).toString('base64url'),
    peers: []
  }
  const sealed = await sealVault('correct horse', payload, { iterations: 1000 })
  const opened = await openVault('correct horse', sealed)
  assert.equal(opened.deviceId, identity.deviceId)
  await assert.rejects(() => openVault('wrong horse', sealed))
  assert.equal(sealed.kdf, 'PBKDF2-SHA256')
  assert.equal(Object.hasOwn(sealed, 'deviceId'), false)
})

test('TLS 1.2 mutual auth interoperates with OpenSSL and carries a frame', { timeout: 20000 }, async () => {
  const ours = await createIdentity('Web')
  const peer = await createIdentity('Phone')
  const foreign = opensslIdentity()
  await new Promise((resolve, reject) => {
    const server = tls.createServer({
      key: foreign.key,
      cert: foreign.cert,
      requestCert: true,
      rejectUnauthorized: false,
      minVersion: 'TLSv1.2',
      maxVersion: 'TLSv1.2',
      ciphers: 'ECDHE-ECDSA-AES128-GCM-SHA256',
      ecdhCurve: 'prime256v1',
      honorCipherOrder: true
    }, (socket) => {
      socket.on('data', (buf) => socket.write(buf))
    })
    server.listen(0, '127.0.0.1', () => {
      const socket = net.connect(server.address().port, '127.0.0.1')
      socket.once('error', reject)
      socket.once('connect', async () => {
        try {
          const conn = await connectTls(socketStream(socket), ours, {
            expectedFingerprint: foreign.fingerprint,
            allowNew: false
          })
          assert.equal(conn.remoteFingerprint, foreign.fingerprint)
          const frame = encodeFrame(1, utf8('{"v":1}'))
          await conn.write(frame)
          const echoed = await conn.readExactly(frame.length)
          assert.equal(hex(echoed), hex(frame))
          conn.close()
          server.close()
          resolve()
        } catch (error) {
          server.close()
          reject(error)
        }
      })
    })
    server.on('tlsClientError', reject)
  })

  await new Promise((resolve, reject) => {
    const tcp = net.createServer((socket) => {
      acceptTls(socketStream(socket), ours, { expectedFingerprint: peer.fingerprint, allowNew: false })
        .then(async (conn) => {
          const got = await conn.readExactly(4)
          await conn.write(got)
          conn.close()
        })
        .catch(reject)
    })
    tcp.listen(0, '127.0.0.1', () => {
      const client = tls.connect({
        host: '127.0.0.1',
        port: tcp.address().port,
        servername: 'stellarsend',
        cert: pem('CERTIFICATE', peer.certDer),
        key: pem('PRIVATE KEY', peer.pkcs8),
        rejectUnauthorized: false,
        minVersion: 'TLSv1.2',
        maxVersion: 'TLSv1.2',
        ciphers: 'ECDHE-ECDSA-AES128-GCM-SHA256',
        ecdhCurve: 'prime256v1'
      }, () => client.write(Buffer.from('ping')))
      const got = []
      client.on('data', (buf) => {
        got.push(...buf)
        if (got.length >= 4) {
          assert.equal(Buffer.from(got.slice(0, 4)).toString(), 'ping')
          client.end()
          tcp.close()
          resolve()
        }
      })
      client.on('error', reject)
    })
  })
})

test('a file transfer uses the same frames as the apps', { timeout: 20000 }, async () => {
  const phone = await createIdentity('Phone')
  const web = await createIdentity('Web')
  const payload = utf8('hello flipsend')
  const digest = hex(sha256(payload))
  let received = null
  const tcp = net.createServer((socket) => {
    acceptTls(socketStream(socket), phone, { expectedFingerprint: web.fingerprint, allowNew: false })
      .then((conn) => receiveOn(conn, {
        identity: phone,
        expected: { deviceId: web.deviceId, fingerprint: web.fingerprint },
        allowNew: false,
        confirmOffer: async () => true,
        onReceived: async (item) => {
          received = new Uint8Array(await item.blob.arrayBuffer())
        }
      }))
      .then((result) => {
        result.done = true
        socket.result = result
      })
      .catch((error) => {
        socket.failed = error
      })
  })
  await new Promise((resolve) => tcp.listen(0, '127.0.0.1', resolve))
  const socket = net.connect(tcp.address().port, '127.0.0.1')
  try {
    const conn = await connectTls(socketStream(socket), web, {
      expectedFingerprint: phone.fingerprint,
      allowNew: false
    })
    await sendOn(conn, {
      identity: web,
      peer: { deviceId: phone.deviceId, fingerprint: phone.fingerprint },
      payload: {
        kind: 'file',
        name: 'note.txt',
        mime: 'text/plain',
        size: payload.length,
        sha256: digest,
        async readAt(offset, length) {
          return payload.subarray(offset, offset + length)
        }
      }
    })
    conn.close()
    await new Promise((resolve, reject) => {
      const start = Date.now()
      const wait = () => {
        if (received) return resolve()
        if (Date.now() - start > 5000) return reject(new Error('The file did not arrive.'))
        setTimeout(wait, 20)
      }
      wait()
    })
    assert.equal(hex(received), hex(payload))
  } finally {
    socket.destroy()
    tcp.close()
  }
})

function opensslIdentity() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'flipsend-'))
  const key = path.join(dir, 'key.pem')
  const cert = path.join(dir, 'cert.pem')
  execFileSync('openssl', ['ecparam', '-name', 'prime256v1', '-genkey', '-noout', '-out', key])
  execFileSync('openssl', ['req', '-new', '-x509', '-key', key, '-out', cert, '-days', '30', '-subj', '/CN=node-peer'])
  const certPem = fs.readFileSync(cert)
  const x509 = new X509Certificate(certPem)
  const spki = x509.publicKey.export({ type: 'spki', format: 'der' })
  return { key: fs.readFileSync(key), cert: certPem, fingerprint: hex(sha256(spki)) }
}
