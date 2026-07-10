package com.aethelhook.app

import android.content.Context
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

// AethelHook's PC has no certificate authority behind it - it's a self-signed cert
// generated once at PC first-run. The phone never validates it against a CA (there
// isn't one); instead it pins the exact SHA-256 fingerprint it received out-of-band
// via the QR pairing scan, which is this system's actual root of trust. This is the
// standard trust-on-first-use pattern, not a weaker substitute for CA validation -
// it's what the QR pairing flow already exists to bootstrap.

// Computes SHA-256 of the presented leaf certificate's encoded (DER) bytes and
// compares to the pinned fingerprint - byte-for-byte the same value the PC computes
// via `cert.GetCertHash(HashAlgorithmName.SHA256)` server-side.
private class PinnedTrustManager(private val expectedFingerprintHex: String) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {}

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("No certificate presented")
        val digest = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
        val hex = digest.joinToString("") { "%02x".format(it) }
        val expected = expectedFingerprintHex.lowercase()
        if (!MessageDigest.isEqual(hex.toByteArray(), expected.toByteArray())) {
            throw CertificateException("Certificate fingerprint mismatch - possible MITM (expected $expected, got $hex)")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}

// Hostname/SAN matching doesn't apply here - the cert is generated once at PC
// first-run but the LAN IP it's reached at is dynamic (DHCP, or a different network
// entirely over Tailscale). The fingerprint pin above is the actual trust check.
private object NoopHostnameVerifier : HostnameVerifier {
    override fun verify(hostname: String?, session: javax.net.ssl.SSLSession?) = true
}

private fun buildTrustManager(fingerprintHex: String) = PinnedTrustManager(fingerprintHex)

private fun buildSslSocketFactory(trustManager: X509TrustManager): SSLSocketFactory =
    SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustManager), SecureRandom())
    }.socketFactory

// Applies pinning to an OkHttpClient.Builder for a specific fingerprint. Callers pass
// the fingerprint fresh off a QR scan (pairing) or from AppPrefs (every call after).
fun OkHttpClient.Builder.pinnedTo(fingerprintHex: String): OkHttpClient.Builder {
    val trustManager = buildTrustManager(fingerprintHex)
    val socketFactory = buildSslSocketFactory(trustManager)
    return this.sslSocketFactory(socketFactory, trustManager)
        .hostnameVerifier(NoopHostnameVerifier)
}

// Same, but only if a fingerprint is actually stored - if the app hasn't been paired
// since this feature shipped, leave the builder untouched (OkHttp's normal defaults),
// which will safely fail closed against the self-signed cert (a clear connection
// error prompting re-pair) rather than silently trusting an unpinned certificate.
fun OkHttpClient.Builder.pinnedFromPrefs(ctx: Context): OkHttpClient.Builder {
    val fingerprint = AppPrefs.getCertFingerprint(ctx)
    return if (fingerprint.isNotBlank()) pinnedTo(fingerprint) else this
}

// Same idea for the raw HttpURLConnection call sites (ApprovalActivity, QuestionActivity,
// PlanReviewActivity) - a no-op if the connection isn't HTTPS or no fingerprint is stored.
fun HttpURLConnection.pinnedFromPrefs(ctx: Context) {
    if (this !is HttpsURLConnection) return
    val fingerprint = AppPrefs.getCertFingerprint(ctx)
    if (fingerprint.isBlank()) return
    val trustManager = buildTrustManager(fingerprint)
    sslSocketFactory = buildSslSocketFactory(trustManager)
    hostnameVerifier = NoopHostnameVerifier
}
