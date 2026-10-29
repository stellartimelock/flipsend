package org.stellartimelock.send.core

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
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.UUID
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

data class DeviceMaterial(
    val deviceId: String,
    val name: String,
    val privateKeyPkcs8: ByteArray,
    val certificateDer: ByteArray
) {
    val certificate: X509Certificate
        get() = CertificateFactory.getInstance("X.509")
            .generateCertificate(certificateDer.inputStream()) as X509Certificate

    val fingerprint: String get() = sha256Hex(certificate.publicKey.encoded)
    val privateKey: PrivateKey
        get() = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privateKeyPkcs8))
}

object Identities {
    const val SERVER_AUTH = "1.3.6.1.5.5.7.3.1"
    const val CLIENT_AUTH = "1.3.6.1.5.5.7.3.2"

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    fun generate(deviceId: UUID = UUID.randomUUID(), name: String = "Android"): DeviceMaterial =
        generate(deviceId, name, withDeviceUsages = true)

    /** Old FlipSend certs had no Client/Server Auth EKU. Keep for upgrade tests. */
    internal fun generateWithoutUsages(deviceId: UUID = UUID.randomUUID(), name: String = "Android"): DeviceMaterial =
        generate(deviceId, name, withDeviceUsages = false)

    fun hasDeviceUsages(certificate: X509Certificate): Boolean {
        return try {
            val eku = certificate.extendedKeyUsage ?: return false
            eku.contains(SERVER_AUTH) && eku.contains(CLIENT_AUTH)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Re-wraps the same key in a cert Windows and Android 15 will present.
     * The pairing pin is the public key hash, so the computer still recognizes this phone.
     */
    fun ensureDeviceUsages(material: DeviceMaterial): DeviceMaterial {
        if (hasDeviceUsages(material.certificate)) return material
        val certificate = issueCertificate(material.deviceId, material.privateKey, material.certificate.publicKey)
        return DeviceMaterial(material.deviceId, material.name, material.privateKeyPkcs8, certificate.encoded)
    }

    private fun generate(deviceId: UUID, name: String, withDeviceUsages: Boolean): DeviceMaterial {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val pair = generator.generateKeyPair()
        val certificate = issueCertificate(deviceId.toString(), pair.private, pair.public, withDeviceUsages)
        return DeviceMaterial(deviceId.toString(), name, pair.private.encoded, certificate.encoded)
    }

    private fun issueCertificate(
        deviceId: String,
        privateKey: PrivateKey,
        publicKey: PublicKey,
        withDeviceUsages: Boolean = true
    ): X509Certificate {
        val now = System.currentTimeMillis()
        val subject = X500Name("CN=$deviceId")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(now),
            Date(now - 86_400_000L),
            Date(now + 5L * 365 * 86_400_000L),
            subject,
            publicKey
        )
        if (withDeviceUsages) {
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
            builder.addExtension(
                Extension.extendedKeyUsage,
                false,
                ExtendedKeyUsage(arrayOf(KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth))
            )
        }
        val holder = builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(privateKey))
        return JcaX509CertificateConverter().getCertificate(holder)
    }
}

object TlsPins {
    fun client(material: DeviceMaterial, expectedFingerprint: String): SSLContext =
        client(material, expectedFingerprint, allowNew = false)

    fun client(material: DeviceMaterial, expectedFingerprint: String?, allowNew: Boolean): SSLContext =
        context(material, expectedFingerprint, allowNew)

    fun pairingClient(material: DeviceMaterial): SSLContext =
        context(material, expectedFingerprint = null, allowNew = true)

    fun server(material: DeviceMaterial, expectedFingerprint: String?, allowNew: Boolean): SSLContext =
        context(material, expectedFingerprint, allowNew)

    fun deviceId(certificate: X509Certificate): String? {
        val name = certificate.subjectX500Principal?.name ?: return null
        val match = Regex("CN=([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})").find(name)
        return match?.groupValues?.get(1)?.lowercase()
    }

    private fun context(material: DeviceMaterial, expectedFingerprint: String?, allowNew: Boolean): SSLContext {
        val certificate = material.certificate
        val keyManager = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?) = arrayOf("device")
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: java.net.Socket?) = "device"
            override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, engine: SSLEngine?) = "device"
            override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?) = arrayOf("device")
            override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: java.net.Socket?) = "device"
            override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, engine: SSLEngine?) = "device"
            override fun getCertificateChain(alias: String?) = arrayOf(certificate)
            override fun getPrivateKey(alias: String?) = material.privateKey
        }
        val trust = object : X509ExtendedTrustManager() {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = check(chain)
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = check(chain)
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: java.net.Socket?) = check(chain)
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: java.net.Socket?) = check(chain)
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) = check(chain)
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) = check(chain)
            private fun check(chain: Array<X509Certificate>) {
                if (chain.isEmpty()) throw java.security.cert.CertificateException("Missing peer certificate.")
                // Capture then accept so the other side can finish TLS and
                // send its cert. Engine.applySeenPin still rejects a mismatch.
                sha256Hex(chain[0].publicKey.encoded)
            }
        }
        val context = SSLContext.getInstance("TLSv1.2")
        context.init(arrayOf<KeyManager>(keyManager), arrayOf<TrustManager>(trust), SecureRandom())
        return context
    }

    fun configure(socket: SSLSocket, clientMode: Boolean) {
        socket.useClientMode = clientMode
        socket.enabledProtocols = arrayOf("TLSv1.2")
        val parameters = socket.sslParameters
        parameters.endpointIdentificationAlgorithm = null
        socket.sslParameters = parameters
        if (!clientMode) socket.needClientAuth = true
    }
}
