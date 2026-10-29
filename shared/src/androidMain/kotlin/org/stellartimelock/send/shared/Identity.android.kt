package org.stellartimelock.send.shared

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Security
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.UUID

actual fun generateIdentity(name: String): DeviceIdentity {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
        Security.addProvider(BouncyCastleProvider())
    }
    val deviceId = UUID.randomUUID().toString()
    val gen = KeyPairGenerator.getInstance("EC")
    gen.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
    val pair = gen.generateKeyPair()
    val now = System.currentTimeMillis()
    val subject = X500Name("CN=$deviceId")
    val builder = JcaX509v3CertificateBuilder(
        subject, BigInteger.valueOf(now),
        Date(now - 86_400_000L), Date(now + 5L * 365 * 86_400_000L),
        subject, pair.public
    )
    builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
    builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
    builder.addExtension(
        Extension.extendedKeyUsage,
        false,
        ExtendedKeyUsage(arrayOf(KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth))
    )
    val holder = builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(pair.private))
    val certificate = JcaX509CertificateConverter().getCertificate(holder)
    val spkiBytes = pair.public.encoded
    val fingerprint = sha256Hex(spkiBytes)
    val spkiBase64Url = base64UrlEncode(spkiBytes)

    // Verify the public key from cert matches
    val factory = KeyFactory.getInstance("EC")
    val pubFromCert = factory.generatePublic(
        java.security.spec.X509EncodedKeySpec(certificate.publicKey.encoded)
    )
    check(sha256Hex(pubFromCert.encoded) == fingerprint)

    return DeviceIdentity(
        deviceId = deviceId,
        name = trimDeviceName(name),
        privateKeyBytes = pair.private.encoded,
        certificateBytes = certificate.encoded,
        fingerprint = fingerprint,
        spkiBase64Url = spkiBase64Url
    )
}
