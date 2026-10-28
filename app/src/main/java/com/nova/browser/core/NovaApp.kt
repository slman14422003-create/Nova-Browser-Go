package com.nova.browser

import android.app.Application
import java.security.KeyStore
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

object Tls {
    @Volatile var trustManager: X509TrustManager? = null; private set
    @Volatile var socketFactory: SSLSocketFactory? = null; private set

    fun install(app: Application) {
        val extra = loadExtraRoots(app)
        val systemTm = defaultTm(null)
        val extraTm = if (extra.isEmpty()) null else defaultTm(KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            extra.forEachIndexed { i, c -> setCertificateEntry("extra-root-$i", c) }
        })
        val merged = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
                systemTm.checkClientTrusted(chain, authType)

            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                try {
                    systemTm.checkServerTrusted(chain, authType)
                } catch (e: Exception) {
                    val fb = extraTm ?: throw e
                    try { fb.checkServerTrusted(chain, authType) } catch (_: Exception) { throw e }
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> =
                systemTm.acceptedIssuers + (extraTm?.acceptedIssuers ?: emptyArray())
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(merged), null)
        trustManager = merged
        socketFactory = ctx.socketFactory
        HttpsURLConnection.setDefaultSSLSocketFactory(ctx.socketFactory)
        SSLContext.setDefault(ctx)
    }

    fun apply(b: OkHttpClient.Builder): OkHttpClient.Builder {
        val tm = trustManager
        val sf = socketFactory
        if (tm != null && sf != null) b.sslSocketFactory(sf, tm)
        return b
    }

    private fun defaultTm(ks: KeyStore?): X509TrustManager {
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        f.init(ks)
        return f.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun loadExtraRoots(app: Application): List<X509Certificate> {
        val cf = CertificateFactory.getInstance("X.509")
        val out = ArrayList<X509Certificate>()
        for (res in intArrayOf(R.raw.extra_roots, R.raw.isrg_root_x1)) {
            runCatching {
                app.resources.openRawResource(res).use { input ->
                    cf.generateCertificates(input).forEach { c -> if (c is X509Certificate) out.add(c) }
                }
            }
        }
        return out
    }
}

class NovaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val app = this
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            runCatching { Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(), 1) }
            runCatching { Tls.install(app) }
        }.start()
    }
}
