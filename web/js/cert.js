import { bitString, integer, octetString, oid, readDer, seq, setOf, tl, utcTime, utf8String } from './asn1.js'
import { concat, hex, randomBytes } from './bytes.js'
import { sha256 } from './sha256.js'
import { derToP1363, p1363ToDer } from './asn1.js'

const ECDSA_SHA256 = '1.2.840.10045.4.3.2'
const EC_PUBLIC = '1.2.840.10045.2.1'
const P256 = '1.2.840.10045.3.1.7'

function extension(dotted, critical, value) {
  const parts = [oid(dotted)]
  if (critical) parts.push(tl(0x01, Uint8Array.of(0xff)))
  parts.push(octetString(value))
  return seq(...parts)
}

export function fingerprintOf(spki) {
  return hex(sha256(spki))
}

export async function createIdentity(name) {
  const deviceId = crypto.randomUUID()
  const pair = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify'])
  const pkcs8 = new Uint8Array(await crypto.subtle.exportKey('pkcs8', pair.privateKey))
  const spki = new Uint8Array(await crypto.subtle.exportKey('spki', pair.publicKey))
  const certDer = await selfSignedCert(deviceId, spki, pair.privateKey)
  return material(deviceId, name, pkcs8, spki, certDer, pair.privateKey, pair.publicKey)
}

export async function loadIdentity(stored) {
  const pkcs8 = stored.pkcs8 instanceof Uint8Array ? stored.pkcs8 : stored.pkcs8
  const spki = stored.spki
  const certDer = stored.certDer
  const privateKey = await crypto.subtle.importKey('pkcs8', pkcs8, { name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign'])
  const publicKey = await crypto.subtle.importKey('spki', spki, { name: 'ECDSA', namedCurve: 'P-256' }, true, ['verify'])
  return material(stored.deviceId, stored.name, pkcs8, spki, certDer, privateKey, publicKey)
}

function material(deviceId, name, pkcs8, spki, certDer, privateKey, publicKey) {
  return {
    deviceId,
    name: trimDeviceName(name),
    pkcs8,
    spki,
    certDer,
    privateKey,
    publicKey,
    fingerprint: fingerprintOf(spki)
  }
}

export function trimDeviceName(name) {
  const clean = String(name || '').replace(/[\u0000-\u001f\u007f]/g, '').trim()
  if (!clean) return 'Web'
  return clean.slice(0, 64)
}

async function selfSignedCert(deviceId, spki, privateKey) {
  const now = Date.now()
  const serial = randomBytes(8)
  serial[0] &= 0x7f
  if (serial.every((b) => b === 0)) serial[7] = 1
  const alg = seq(oid(ECDSA_SHA256))
  const name = seq(setOf(seq(oid('2.5.4.3'), utf8String(deviceId))))
  const validity = seq(utcTime(new Date(now - 86400000)), utcTime(new Date(now + 5 * 365 * 86400000)))
  const basic = extension('2.5.29.19', true, seq(tl(0x01, Uint8Array.of(0x00))))
  const usage = extension('2.5.29.15', true, bitString(Uint8Array.of(0x80), 7))
  const eku = extension('2.5.29.37', false, seq(oid('1.3.6.1.5.5.7.3.1'), oid('1.3.6.1.5.5.7.3.2')))
  const tbs = seq(
    tl(0xa0, integer(Uint8Array.of(2))),
    integer(serial),
    alg,
    name,
    validity,
    name,
    spki,
    tl(0xa3, seq(basic, usage, eku))
  )
  const sig = p1363ToDer(new Uint8Array(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, privateKey, tbs)))
  return seq(tbs, alg, bitString(sig, 0))
}

export function parseCertificate(der) {
  const cert = readDer(der, 0)
  if (cert.tag !== 0x30) throw new Error('Bad certificate.')
  const tbs = readDer(der, cert.value)
  if (tbs.tag !== 0x30) throw new Error('Bad certificate.')
  let p = tbs.value
  const first = readDer(der, p)
  if (first.tag === 0xa0) p = first.end
  p = readDer(der, p).end
  p = readDer(der, p).end
  p = readDer(der, p).end
  p = readDer(der, p).end
  p = readDer(der, p).end
  const spki = readDer(der, p)
  if (spki.tag !== 0x30) throw new Error('Bad certificate public key.')
  const sigAlg = readDer(der, tbs.end)
  const sigBits = readDer(der, sigAlg.end)
  if (sigBits.tag !== 0x03 || sigBits.body.length < 2) throw new Error('Bad certificate signature.')
  return {
    tbs: der.subarray(tbs.start, tbs.end),
    spki: der.subarray(spki.start, spki.end),
    signature: sigBits.body.subarray(1)
  }
}

export async function publicKeyFromCert(der) {
  const parsed = parseCertificate(der)
  const key = await crypto.subtle.importKey('spki', parsed.spki, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify'])
  const ok = await crypto.subtle.verify(
    { name: 'ECDSA', hash: 'SHA-256' },
    key,
    derToP1363(parsed.signature),
    parsed.tbs
  )
  if (!ok) throw new Error('The peer certificate signature is not valid.')
  return { key, spki: parsed.spki, fingerprint: fingerprintOf(parsed.spki) }
}

export { ECDSA_SHA256, EC_PUBLIC, P256 }
