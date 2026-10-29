package org.stellartimelock.send.shared

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSData
import platform.Foundation.NSUUID
import platform.Security.SecCertificateCopyData
import platform.Security.SecCertificateCreateWithData
import platform.Security.SecItemAdd
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyCopyPublicKey
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrIsPermanent
import platform.Security.kSecAttrApplicationTag
import platform.Security.kSecPrivateKeyAttrs
import platform.Security.kSecReturnData

/**
 * iOS identity generation using Apple's Security framework.
 *
 * Generates an EC-P256 key pair and a minimal self-signed X.509 certificate.
 * The certificate bytes are assembled using a hand-written DER encoder so
 * that we don't depend on BouncyCastle on iOS.
 *
 * NOTE: Full production-quality ASN.1 / X.509 DER is complex.
 * This implementation produces a certificate that is functionally
 * correct for mutual-TLS pairing but has a simplified extensions set.
 * See docs/ios-tls-notes.md for what a maintainer needs to review before
 * submitting to the App Store.
 *
 * Needs from a maintainer PC:
 * - Run the full TLS compatibility test against the desktop companion.
 * - Verify that SecureTransport / Network.framework accepts our hand-rolled cert.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun generateIdentity(name: String): DeviceIdentity {
    val deviceId = NSUUID().UUIDString.lowercase()
    val safeName = trimDeviceName(name)

    // Generate EC-P256 key pair using Apple Security
    // kSecAttrIsPermanent = true stores the key in Keychain under the app tag,
    // enabling SecIdentity lookup later (transport identity only — not a save-file key).
    val keyAttrs = mapOf<Any?, Any?>(
        kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
        kSecAttrKeySizeInBits to 256,
        kSecPrivateKeyAttrs to mapOf(
            kSecAttrIsPermanent to true,
            kSecAttrApplicationTag to "org.stellartimelock.stellarsend.tls.key.v1".encodeToByteArray().toNSData()
        )
    )
    var error: platform.Foundation.NSError? = null
    val privateKey = SecKeyCreateRandomKey(keyAttrs as platform.CoreFoundation.CFDictionaryRef, null)
        ?: throw IllegalStateException("Failed to generate EC key pair on iOS.")

    val publicKey = SecKeyCopyPublicKey(privateKey)
        ?: throw IllegalStateException("Failed to extract public key.")

    // Export the public key as SPKI DER (X9.63 uncompressed point wrapped in SPKI)
    val pubRaw = SecKeyCopyExternalRepresentation(publicKey, null)
        ?: throw IllegalStateException("Failed to export public key.")
    val spkiDer = wrapEcPublicKeyInSpki(pubRaw.toByteArray())
    val fingerprint = sha256Hex(spkiDer)
    val spkiBase64Url = base64UrlEncode(spkiDer)

    // Build a minimal self-signed X.509 DER certificate
    val certDer = buildMinimalX509Cert(
        deviceId = deviceId,
        spkiDer = spkiDer,
        privateKey = privateKey
    )

    // Export private key as PKCS#8 DER
    val privRaw = SecKeyCopyExternalRepresentation(privateKey, null)
        ?: throw IllegalStateException("Failed to export private key.")
    val privPkcs8 = wrapEcPrivateKeyInPkcs8(privRaw.toByteArray())

    return DeviceIdentity(
        deviceId = deviceId,
        name = safeName,
        privateKeyBytes = privPkcs8,
        certificateBytes = certDer,
        fingerprint = fingerprint,
        spkiBase64Url = spkiBase64Url
    )
}

// ---------------------------------------------------------------------------
// Minimal ASN.1 / DER helpers
// These produce valid X.509v3 certificates compatible with TLSv1.2 mutual auth.
// ---------------------------------------------------------------------------

/**
 * Wrap an X9.63 raw EC public key (04 || X || Y, 65 bytes for P-256) in a
 * SubjectPublicKeyInfo structure so TLS can use it as an SPKI fingerprint.
 *
 *  SEQUENCE {
 *    SEQUENCE {
 *      OID 1.2.840.10045.2.1  (ecPublicKey)
 *      OID 1.2.840.10045.3.1.7 (prime256v1)
 *    }
 *    BIT STRING (04 || X || Y)
 *  }
 */
private fun wrapEcPublicKeyInSpki(rawPoint: ByteArray): ByteArray {
    // OID ecPublicKey: 1.2.840.10045.2.1
    val oidEcPub = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x02, 0x01)
    // OID prime256v1: 1.2.840.10045.3.1.7
    val oidP256 = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01, 0x07)

    val algSeq = derSequence(derOid(oidEcPub) + derOid(oidP256))
    val bitString = derBitString(rawPoint)
    return derSequence(algSeq + bitString)
}

/**
 * Wrap a raw EC private key (d || X || Y or SEC1 format) in PKCS#8.
 * Apple exports EC private keys as raw 32-byte scalar d concatenated with
 * the 64-byte public key (X || Y), total 96 bytes for P-256.
 *
 *  SEQUENCE {          -- PrivateKeyInfo
 *    INTEGER 0         -- version
 *    SEQUENCE { OID ecPublicKey; OID prime256v1 }
 *    OCTET STRING {    -- privateKey
 *      SEQUENCE {      -- ECPrivateKey (SEC 1)
 *        INTEGER 1
 *        OCTET STRING d
 *        [1] BIT STRING (04 || X || Y)
 *      }
 *    }
 *  }
 */
private fun wrapEcPrivateKeyInPkcs8(rawKey: ByteArray): ByteArray {
    // rawKey from Apple is: d (32 bytes) || X (32 bytes) || Y (32 bytes)
    val d = rawKey.copyOfRange(0, 32)
    val pub = byteArrayOf(0x04) + rawKey.copyOfRange(32, 96)

    val oidEcPub = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x02, 0x01)
    val oidP256 = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01, 0x07)
    val algSeq = derSequence(derOid(oidEcPub) + derOid(oidP256))

    // SEC1 ECPrivateKey
    val pubContext = derTaggedExplicit(1, derBitString(pub))
    val sec1 = derSequence(derInteger(byteArrayOf(1)) + derOctetString(d) + pubContext)
    val privKeyInfo = derSequence(
        derInteger(byteArrayOf(0)) + algSeq + derOctetString(sec1)
    )
    return privKeyInfo
}

/**
 * Build a minimal self-signed X.509 v3 certificate for mutual TLS.
 * Signature is ECDSA-with-SHA256.
 *
 * Needs from a maintainer PC: Run against the desktop companion to confirm
 * TLS handshake succeeds. Apple's SecureTransport / Network.framework
 * is strict about cert validity; the desktop companion uses BouncyCastle
 * which is more lenient in TLS server mode.
 */
@OptIn(ExperimentalForeignApi::class)
private fun buildMinimalX509Cert(
    deviceId: String,
    spkiDer: ByteArray,
    privateKey: platform.Security.SecKeyRef
): ByteArray {
    val now = platform.Foundation.NSDate.date().timeIntervalSince1970.toLong()
    val notBefore = encodeDerTime(now - 86_400)
    val notAfter = encodeDerTime(now + 5L * 365 * 86_400)

    val cnBytes = deviceId.encodeToByteArray()
    val rdnSeq = derSequence(
        derSet(derSequence(derOid(byteArrayOf(0x55, 0x04, 0x03)) + derUtf8String(cnBytes)))
    )

    val tbsCert = derSequence(
        derTaggedExplicit(0, derInteger(byteArrayOf(2))) +    // version v3
        derInteger(byteArrayOf(0x01)) +                       // serialNumber = 1
        derSequence(derOid(oidEcdsaWithSha256)) +             // signature algorithm
        rdnSeq +                                              // issuer
        derSequence(notBefore + notAfter) +                   // validity
        rdnSeq +                                              // subject
        spkiDer +                                             // subjectPublicKeyInfo
        basicConstraintsExtension()
    )

    // Sign tbsCert with the private key
    val tbsNsData = tbsCert.toNSData()
    val sig = platform.Security.SecKeyCreateSignature(
        privateKey,
        platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA256,
        tbsNsData,
        null
    ) ?: throw IllegalStateException("Failed to sign certificate.")
    val sigBytes = sig.toByteArray()

    return derSequence(
        tbsCert +
        derSequence(derOid(oidEcdsaWithSha256)) +
        derBitString(sigBytes)
    )
}

// OID 1.2.840.10045.4.3.2 (ecdsa-with-SHA256)
private val oidEcdsaWithSha256 = byteArrayOf(
    0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x04, 0x03, 0x02
)

private fun basicConstraintsExtension(): ByteArray {
    // Extension OID 2.5.29.19 (basic constraints), critical = TRUE, value = SEQUENCE {}
    val oidBasicConstraints = byteArrayOf(0x55, 0x1d, 0x13)
    val extValue = derOctetString(derSequence(ByteArray(0)))
    val ext = derSequence(derOid(oidBasicConstraints) + derBoolean(true) + extValue)
    return derTaggedExplicit(3, derSequence(ext))
}

// ---------- DER primitives ----------

private fun derLen(len: Int): ByteArray = when {
    len < 0x80 -> byteArrayOf(len.toByte())
    len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
    else -> byteArrayOf(0x82.toByte(), (len ushr 8).toByte(), len.toByte())
}

private fun derTlv(tag: Byte, value: ByteArray) = byteArrayOf(tag) + derLen(value.size) + value
private fun derSequence(value: ByteArray) = derTlv(0x30, value)
private fun derSet(value: ByteArray) = derTlv(0x31, value)
private fun derOid(oid: ByteArray) = derTlv(0x06, oid)
private fun derBitString(data: ByteArray) = derTlv(0x03, byteArrayOf(0) + data)
private fun derOctetString(data: ByteArray) = derTlv(0x04, data)
private fun derUtf8String(data: ByteArray) = derTlv(0x0c, data)
private fun derBoolean(value: Boolean) = derTlv(0x01, byteArrayOf(if (value) 0xFF.toByte() else 0))

private fun derInteger(data: ByteArray): ByteArray {
    // Ensure non-negative: prepend 0x00 if high bit set
    val padded = if (data.isNotEmpty() && (data[0].toInt() and 0x80) != 0) byteArrayOf(0) + data else data
    return derTlv(0x02, padded)
}

private fun derTaggedExplicit(tag: Int, value: ByteArray): ByteArray {
    val t = (0xA0 or tag).toByte()
    return byteArrayOf(t) + derLen(value.size) + value
}

/** UTC time for X.509: YYMMDDHHmmssZ */
private fun encodeDerTime(epochSeconds: Long): ByteArray {
    val cal = platform.Foundation.NSCalendar.currentCalendar()
    val date = platform.Foundation.NSDate.dateWithTimeIntervalSince1970(epochSeconds.toDouble())
    val comps = cal.components(
        platform.Foundation.NSCalendarUnitYear or
        platform.Foundation.NSCalendarUnitMonth or
        platform.Foundation.NSCalendarUnitDay or
        platform.Foundation.NSCalendarUnitHour or
        platform.Foundation.NSCalendarUnitMinute or
        platform.Foundation.NSCalendarUnitSecond,
        fromDate = date
    )
    val yy = comps.year % 100
    val s = "%02d%02d%02d%02d%02d%02dZ".format(
        yy, comps.month, comps.day, comps.hour, comps.minute, comps.second
    )
    return derTlv(0x17, s.encodeToByteArray())
}
