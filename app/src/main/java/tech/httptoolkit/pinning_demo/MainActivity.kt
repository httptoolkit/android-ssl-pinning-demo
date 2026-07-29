package tech.httptoolkit.pinning_demo

import android.graphics.drawable.Drawable
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.Button
import android.widget.Toast
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.android.volley.RequestQueue
import com.android.volley.toolbox.BasicNetwork
import com.android.volley.toolbox.HurlStack
import com.android.volley.toolbox.NoCache
import com.android.volley.toolbox.StringRequest
import com.appmattus.certificatetransparency.certificateTransparencyHostnameVerifier
import com.appmattus.certificatetransparency.certificateTransparencyInterceptor
import com.appmattus.certificatetransparency.certificateTransparencyTrustManager
import com.datatheorem.android.trustkit.TrustKit
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.security.KeyStore
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.*

// Every button uses its own hostname, so that whatever blocks (or fails to block) a request is
// unambiguously the feature that button demonstrates, rather than another button's config or the
// platform defaults leaking across. See network_security_config.xml for the matching config.

// No pinning at all - the baseline that everything else is compared against:
const val UNGUARDED_HOST = "testserver.host"

// Hosts issued by testserver.host's own CA, which we pin against. That CA's root is served in the
// chain (unlike most public CAs) so even the raw socket check below can pin it directly, and it
// doesn't rotate underneath us:
const val CONFIG_PINNED_HOST = "rsa2048--untrusted-root.testserver.host"
const val TRUSTKIT_PINNED_HOST = "rsa4096--untrusted-root.testserver.host"
const val CODE_PINNED_HOST = "rsa8192--untrusted-root.testserver.host"

// Certificate Transparency only exists for publicly trusted CAs, so the Appmattus buttons use
// public (Google Trust Services) hosts instead, one per integration point.
//
// These deliberately use testserver.host's combined modes, which accept either protocol or TLS
// version rather than requiring one. A single-mode host (e.g. h2-only) is reachable by some of
// our HTTP stacks but not others - HttpsURLConnection speaks only HTTP/1.1 - and the resulting
// handshake failure surfaces as an unrelated-looking connection error, which is exactly the kind
// of misattribution the per-button hostnames exist to avoid:
const val CT_HOSTNAME_VERIFIER_HOST = "tls-v1-2--tls-v1-3.testserver.host"
const val CT_OKHTTP_HOST = "http2--http1.testserver.host"
const val CT_TRUST_MANAGER_HOST = "http1--http2.testserver.host"

// Android's own CT enforcement, configured in network_security_config.xml. Unavailable before
// Android 16 (API 36), opt-in there, and on by default from Android 17 (API 37).
const val NATIVE_CT_HOST = "tls-v1-3--tls-v1-2.testserver.host"

// testserver.host's own CA, which serves its root in the chain, so we can pin either:
const val TESTSERVER_ROOT_PK_SHA256 = "SOCynZ/Y0dEFXgzk6JBT75LF3JhnwWGNJ4SMOmU8CIY="
const val TESTSERVER_INTERMEDIATE_PK_SHA256 = "UoKxGKz3meAmeM9JwHt6hfBs6jG0BqwgJ4vuYBiCeG4="


@Suppress("UNUSED_PARAMETER")
@DelicateCoroutinesApi
class MainActivity : AppCompatActivity() {

    private var flutterEngine: FlutterEngine? = null

    // A detached WebView never lays out, and so never completes a page load. Attaching it (at 1x1,
    // so it's effectively invisible) is what makes the load actually run and report back.
    private fun createAttachedWebView(): WebView {
        val webView = WebView(this)
        findViewById<ViewGroup>(android.R.id.content).addView(webView, 1, 1)
        return webView
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Deal with SDK 35+ edge to edge layout:
        val scrollView = findViewById<View>(R.id.root_scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { view, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(
                left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom
            )
            windowInsets
        }

        TrustKit.initializeWithNetworkSecurityConfiguration(this@MainActivity)

        // Prepare the flutter engine:
        flutterEngine = FlutterEngine(this)
        flutterEngine!!.dartExecutor.executeDartEntrypoint(
            DartExecutor.DartEntrypoint.createDefault()
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        flutterEngine?.destroy()
    }

    private fun onStart(@IdRes id: Int) {
        GlobalScope.launch(Dispatchers.Main) {
            val button = findViewById<Button>(id)
            button.setBackgroundColor(
                ContextCompat.getColor(this@MainActivity, R.color.purple_500)
            )
            button.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
        }
    }

    private fun onSuccess(@IdRes id: Int) {
        println("onSuccess")
        GlobalScope.launch(Dispatchers.Main) {
            println("dispatched")
            val button = findViewById<Button>(id)
            button.setBackgroundColor(
                ContextCompat.getColor(this@MainActivity, R.color.success)
            )
            val img: Drawable = ContextCompat.getDrawable(
                this@MainActivity,
                R.drawable.baseline_check_circle_24
            )!!
            button.setCompoundDrawablesWithIntrinsicBounds(img, null, null, null)
            button.contentDescription = "${button.text} - Success"
        }
    }

    private fun onError(@IdRes id: Int, message: String) {
        GlobalScope.launch(Dispatchers.Main) {
            val button = findViewById<Button>(id)
            button.setBackgroundColor(
                ContextCompat.getColor(this@MainActivity, R.color.failure)
            )
            val img: Drawable = ContextCompat.getDrawable(
                this@MainActivity,
                R.drawable.baseline_cancel_24
            )!!
            button.setCompoundDrawablesWithIntrinsicBounds(img, null, null, null)
            button.contentDescription = "${button.text} - Failed with error: $message"

            val duration = Toast.LENGTH_LONG
            val toast = Toast.makeText(this@MainActivity, message, duration)
            toast.show()
        }
    }

    fun sendHttpRequest(view: View) {
        GlobalScope.launch(Dispatchers.IO) {
            onStart(R.id.http_request)
            try {
                val mURL = URL("http://$UNGUARDED_HOST")
                with(mURL.openConnection() as HttpURLConnection) {
                    println("URL: ${this.url}")
                    println("Response Code: ${this.responseCode}")
                }

                onSuccess(R.id.http_request)
            } catch (e: Throwable) {
                println(e)
                onError(R.id.http_request, e.toString())
            }
        }
    }

    fun sendIgnoreProxyHttpRequest(view: View) {
        GlobalScope.launch(Dispatchers.IO) {
            onStart(R.id.ignore_proxy_http_request)
            try {
                val mURL = URL("http://$UNGUARDED_HOST")
                with(mURL.openConnection(Proxy.NO_PROXY) as HttpURLConnection) {
                    println("URL: ${this.url}")
                    println("Response Code: ${this.responseCode}")
                }

                onSuccess(R.id.ignore_proxy_http_request)
            } catch (e: Throwable) {
                println(e)
                onError(R.id.ignore_proxy_http_request, e.toString())
            }
        }
    }

    fun sendUnpinned(view: View) {
        GlobalScope.launch(Dispatchers.IO) {
            onStart(R.id.unpinned)
            try {
                val mURL = URL("https://$UNGUARDED_HOST")
                with(mURL.openConnection() as HttpsURLConnection) {
                    println("URL: ${this.url}")
                    println("Response Code: ${this.responseCode}")
                }

                onSuccess(R.id.unpinned)
            } catch (e: Throwable) {
                println(e)
                onError(R.id.unpinned, e.toString())
            }
        }
    }

    fun sendUnpinnedWebView(view: View) {
        onStart(R.id.webview_unpinned)
        val webView = createAttachedWebView()

        var connectionFailed = false

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                onError(R.id.webview_unpinned, error.toString())
                connectionFailed = true
                handler?.cancel()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame != true) return

                println("Unpinned WebView error: $error")
                onError(R.id.webview_unpinned, error.toString())
                connectionFailed = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (connectionFailed) return

                println("Unpinned WebView loaded OK")
                onSuccess(R.id.webview_unpinned)
            }
        }
        webView.loadUrl("https://$UNGUARDED_HOST")
    }

    fun sendUnpinnedHttp3(view: View) {
        onStart(R.id.http3_unpinned)
        val context = this@MainActivity

        val cronetEngine = org.chromium.net.CronetEngine.Builder(context)
            .enableQuic(true)
            .addQuicHint("www.google.com", 443, 443)
            .build()
        val requestBuilder = cronetEngine.newUrlRequestBuilder(
            "https://www.google.com/",
            object : org.chromium.net.UrlRequest.Callback() {
                override fun onRedirectReceived(request: org.chromium.net.UrlRequest, info: org.chromium.net.UrlResponseInfo, newLocationUrl: String) {}
                override fun onReadCompleted(request: org.chromium.net.UrlRequest, info: org.chromium.net.UrlResponseInfo, byteBuffer: java.nio.ByteBuffer) {}
                override fun onSucceeded(request: org.chromium.net.UrlRequest, info: org.chromium.net.UrlResponseInfo) {}

                override fun onResponseStarted(request: org.chromium.net.UrlRequest, info: org.chromium.net.UrlResponseInfo) {
                    request.cancel()
                    if (info.negotiatedProtocol == "h3") {
                        onSuccess(R.id.http3_unpinned)
                    } else {
                        // Toast the downgrade to highlight it, but still count the request as a
                        // success - it was sent, just not over HTTP/3:
                        onError(R.id.http3_unpinned, "Expected HTTP/3, got ${info.negotiatedProtocol}")
                        onSuccess(R.id.http3_unpinned)
                    }
                }

                override fun onFailed(request: org.chromium.net.UrlRequest, info: org.chromium.net.UrlResponseInfo?, error: org.chromium.net.CronetException) {
                    println("h3 request failed: $error")
                    onError(R.id.http3_unpinned, error.toString())
                }
            },
            java.util.concurrent.Executors.newSingleThreadExecutor()
        )

        requestBuilder
            .disableCache()
            .build()
            .start()
    }

    fun sendConfigPinned(view: View) {
        GlobalScope.launch(Dispatchers.IO) {
            onStart(R.id.config_pinned)
            try {
                // Pinned by hash in network config:
                val mURL = URL("https://$CONFIG_PINNED_HOST")
                with(mURL.openConnection() as HttpsURLConnection) {
                    println("URL: ${this.url}")
                    println("Response Code: ${this.responseCode}")
                }

                onSuccess(R.id.config_pinned)
            } catch (e: Throwable) {
                println(e)
                onError(R.id.config_pinned, e.toString())
            }
        }
    }

    // Manually pinned by building an SSLContext that trusts only the correct certificate, and then
    // connecting with the native HttpsUrlConnection API:
    fun sendContextPinned(view: View) {
        GlobalScope.launch(Dispatchers.IO) {
            onStart(R.id.context_pinned)

            val cf = CertificateFactory.getInstance("X.509")
            val caStream = BufferedInputStream(resources.openRawResource(R.raw.testserver_root))
            val caCertificate = cf.generateCertificate(caStream)

            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            keyStore.load(null)
            keyStore.setCertificateEntry("ca", caCertificate)

            val trustManagerFactory = TrustManagerFactory
                .getInstance(TrustManagerFactory.getDefaultAlgorithm())
            trustManagerFactory.init(keyStore)

            try {
                val context = SSLContext.getInstance("TLS")
                context.init(null, trustManagerFactory.trustManagers, null)

                val mURL = URL("https://$CODE_PINNED_HOST")
                with(mURL.openConnection() as HttpsURLConnection) {
                    this.sslSocketFactory = context.socketFactory

                    println("URL: ${this.url}")
                    println("Response Code: ${this.responseCode}")
                }

                onSuccess(R.id.context_pinned)
            } catch (e: Throwable) {
                println(e)
                onError(R.id.context_pinned, e.toString())
            }
        }
    }

    fun sendOkHttpPinned(view: View) {
        GlobalScope.launch(Dispatchers.IO) {
            onStart(R.id.okhttp_pinned)

            try {
                val hostname = CODE_PINNED_HOST
                val certificatePinner = CertificatePinner.Builder()
                    .add(hostname, "sha256/${TESTSERVER_ROOT_PK_SHA256}")
                    .add(hostname, "sha256/${TESTSERVER_INTERMEDIATE_PK_SHA256}")
                    .build()

                val client = OkHttpClient.Builder()
                    .certificatePinner(certificatePinner)
                    .build()
                val request = Request.Builder()
                    .url("https://$CODE_PINNED_HOST")
                    .build()

                client.newCall(request).execute().use { response ->
                    println("URL: ${request.url}")
                    println("Response Code: ${response.code}")
                }

                onSuccess(R.id.okhttp_pinned)
            } catch (e: Throwable) {
                println(e)
                onError(R.id.okhttp_pinned, e.toString())
            }
        }
    }

    fun sendVolleyPinned(view: View) {
        onStart(R.id.volley_pinned)

        try {
            // Create an HTTP client that only trusts our specific certificate:
            val cf = CertificateFactory.getInstance("X.509")
            val caStream = BufferedInputStream(resources.openRawResource(R.raw.testserver_root))
            val ca = cf.generateCertificate(caStream)
            caStream.close()

            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            keyStore.load(null, null)
            keyStore.setCertificateEntry("ca", ca)

            val trustManagerAlgorithm = TrustManagerFactory.getDefaultAlgorithm()
            val trustManagerFactory = TrustManagerFactory.getInstance(trustManagerAlgorithm)
            trustManagerFactory.init(keyStore)

            val context = SSLContext.getInstance("TLS")
            context.init(null, trustManagerFactory.trustManagers, null)

            val requestQueue = RequestQueue(
                NoCache(),
                BasicNetwork(HurlStack(null, context.socketFactory))
            )
            requestQueue.start()

            // Make a request using that client:
            val stringRequest = StringRequest(
                com.android.volley.Request.Method.GET,
                "https://$CODE_PINNED_HOST",
                { _ ->
                    println("Volley success")
                    this@MainActivity.onSuccess(R.id.volley_pinned)
                },
                {
                    println(it.toString())
                    this@MainActivity.onError(R.id.volley_pinned, it.toString())
                }
            )

            requestQueue.add(stringRequest)
        } catch (e: Throwable) {
            println(e)
            onError(R.id.volley_pinned, e.toString())
        }
    }

    fun sendTrustKitPinned(view: View) {
        GlobalScope.launch(Dispatchers.IO) {
            onStart(R.id.trustkit_pinned)
            try {
                val mURL = URL("https://$TRUSTKIT_PINNED_HOST")
                with(mURL.openConnection() as HttpsURLConnection) {
                    this.sslSocketFactory = TrustKit.getInstance().getSSLSocketFactory(
                            TRUSTKIT_PINNED_HOST
                    )
                    println("URL: ${this.url}")
                    println("Response Code: ${this.responseCode}")
                }

                onSuccess(R.id.trustkit_pinned)
            } catch (e: Throwable) {
                println(e)
                onError(R.id.trustkit_pinned, e.toString())
            }
        }
    }

    fun sendAppmattusCTChecked(view: View) {
        GlobalScope.launch(Dispatchers.IO) {
            onStart(R.id.appmattus_ct_checked)

            try {
                val mURL = URL("https://$CT_HOSTNAME_VERIFIER_HOST")
                with(mURL.openConnection() as HttpsURLConnection) {
                    this.hostnameVerifier = certificateTransparencyHostnameVerifier(this.hostnameVerifier)
                    println("URL: ${this.url}")
                    println("Response Code: ${this.responseCode}")
                }

                onSuccess(R.id.appmattus_ct_checked)
            } catch (e: Throwable) {
                println(e)
                onError(R.id.appmattus_ct_checked, e.toString())
            }
        }
    }

    fun sendAppmattusOkHttpCTChecked(view: View) {
        GlobalScope.launch(Dispatchers.IO) {
            onStart(R.id.appmattus_okhttp_ct_checked)
            try {
                val appmattusInterceptor = certificateTransparencyInterceptor()
                val client = OkHttpClient.Builder().apply {
                    addNetworkInterceptor(appmattusInterceptor)
                }.build()
                val request = Request.Builder()
                    .url("https://$CT_OKHTTP_HOST")
                    .build()

                client.newCall(request).execute().use { response ->
                    println("URL: ${request.url}")
                    println("Response Code: ${response.code}")
                }

                onSuccess(R.id.appmattus_okhttp_ct_checked)
            } catch (e: Throwable) {
                println(e)
                onError(R.id.appmattus_okhttp_ct_checked, e.toString())
            }
        }
    }

    fun sendAppmattusRawCTChecked(view: View) {
        GlobalScope.launch(Dispatchers.IO) {
            onStart(R.id.appmattus_raw_ct_checked)

            // The bundle holds both the self-signed root and its cross-signed copy, as servers
            // may send either, and path building needs whichever one terminates the chain:
            val cf = CertificateFactory.getInstance("X.509")
            val caStream = BufferedInputStream(resources.openRawResource(R.raw.gts_root_r1))
            val caCertificates = cf.generateCertificates(caStream)

            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            keyStore.load(null)
            caCertificates.forEachIndexed { i, ca -> keyStore.setCertificateEntry("ca$i", ca) }

            val trustManagerFactory = TrustManagerFactory
                .getInstance(TrustManagerFactory.getDefaultAlgorithm())
            trustManagerFactory.init(keyStore)

            val originalTrustManagers = trustManagerFactory.trustManagers;

            // Wrap the native trust managers with Appmattus's CT implementation:
            val ctWrappedTrustManagers = originalTrustManagers.map { tm ->
                certificateTransparencyTrustManager(tm as X509TrustManager)
            }.toTypedArray()

            try {
                val context = SSLContext.getInstance("TLS")
                context.init(null, ctWrappedTrustManagers, null)

                val mURL = URL("https://$CT_TRUST_MANAGER_HOST")
                with(mURL.openConnection() as HttpsURLConnection) {
                    this.sslSocketFactory = context.socketFactory

                    println("URL: ${this.url}")
                    println("Response Code: ${this.responseCode}")
                }
                onSuccess(R.id.appmattus_raw_ct_checked)
            } catch (e: Throwable) {
                println(e)
                onError(R.id.appmattus_raw_ct_checked, e.toString())
            }
        }
    }

    // Checked by the platform itself, via <certificateTransparency> in the network security
    // config. No CT code in the app at all - on Android 15 and below this is simply unenforced,
    // so the button passes without proving anything:
    fun sendNativeCTRequest(view: View) {
        GlobalScope.launch(Dispatchers.IO) {
            onStart(R.id.native_ct)
            try {
                val mURL = URL("https://$NATIVE_CT_HOST")
                with(mURL.openConnection() as HttpsURLConnection) {
                    println("URL: ${this.url}")
                    println("Response Code: ${this.responseCode}")
                }

                onSuccess(R.id.native_ct)
            } catch (e: Throwable) {
                println(e)
                onError(R.id.native_ct, e.toString())
            }
        }
    }

    fun sendFlutterRequest(view: View) {
        onStart(R.id.flutter_request)

        val channel = MethodChannel(flutterEngine!!.dartExecutor.binaryMessenger, "tech.httptoolkit.pinning_demo.flutter_channel")

        println("Calling Dart method from Kotlin...")
        channel.invokeMethod("sendRequest", "https://$UNGUARDED_HOST/", object : MethodChannel.Result {
            override fun success(result: Any?) {
                println("Success from Dart: $result")
                onSuccess(R.id.flutter_request)
            }

            override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                println("Error: $errorCode - $errorMessage")
                onError(R.id.flutter_request, errorMessage ?: "Unknown error")
            }

            override fun notImplemented() {
                println("Method not implemented on Dart side.")
            }
        })
    }

    // Manually pinned at the lowest level: creating a raw TLS connection, disabling all checks,
    // and then directly analysing the certificate that's received after connection, before doing
    // HTTP by just writing & reading raw strings. Not a good idea, but the hardest to unpin!
    fun sendCustomRawSocketPinned(view: View) {
        GlobalScope.launch(Dispatchers.IO) {
            onStart(R.id.custom_raw_socket_pinned)
            try {
                // Disable trust manager checks - we'll check the certificate manually ourselves later
                val trustManager = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun getAcceptedIssuers(): Array<X509Certificate?>? {
                        return null
                    }

                    override fun checkClientTrusted(certs: Array<X509Certificate?>?, authType: String?) {}
                    override fun checkServerTrusted(certs: Array<X509Certificate?>?, authType: String?) {}
                })

                val context = SSLContext.getInstance("TLS")
                context.init(null, trustManager, null)

                val socket = context.socketFactory.createSocket(CODE_PINNED_HOST, 443) as SSLSocket

                val certs = socket.session.peerCertificates

                if (!certs.any { cert ->
                        doesCertMatchPin(TESTSERVER_ROOT_PK_SHA256, cert) ||
                        doesCertMatchPin(TESTSERVER_INTERMEDIATE_PK_SHA256, cert)
                }) {
                    socket.close() // Close the socket immediately without sending a request
                    throw Error("Unrecognized cert hash.")
                }

                // Send a real request, just to make it clear that we trust the connection:
                val pw = PrintWriter(socket.outputStream)
                pw.println("GET / HTTP/1.1")
                pw.println("Host: $CODE_PINNED_HOST")
                pw.println("")
                pw.flush()

                val br = BufferedReader(InputStreamReader(socket.inputStream))
                val responseLine = br.readLine()

                println("Response was: $responseLine")
                socket.close()

                onSuccess(R.id.custom_raw_socket_pinned)
            } catch (e: Throwable) {
                println(e)
                onError(R.id.custom_raw_socket_pinned, e.toString())
            }
        }
    }

    private fun doesCertMatchPin(pin: String, cert: Certificate): Boolean {
        val certHash = cert.publicKey.encoded.toByteString().sha256()
        return certHash == pin.decodeBase64()
    }
}
