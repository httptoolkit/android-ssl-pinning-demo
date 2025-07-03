package tech.httptoolkit.pinning_demo

import android.graphics.drawable.Drawable
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.webkit.*
import android.widget.Button
import android.widget.Toast
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.android.volley.RequestQueue
import com.android.volley.toolbox.BasicNetwork
import com.android.volley.toolbox.HurlStack
import com.android.volley.toolbox.NoCache
import com.android.volley.toolbox.StringRequest
import com.appmattus.certificatetransparency.certificateTransparencyHostnameVerifier
import com.appmattus.certificatetransparency.certificateTransparencyInterceptor
import com.appmattus.certificatetransparency.certificateTransparencyTrustManager
import com.appmattus.certificatetransparency.installCertificateTransparencyProvider
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
import java.net.URL
import java.security.KeyStore
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.*

// We check for both the long-term root & intermediate, because some servers don't seem to
// include the ISRG in the chain (assuming it's in our trust store). Unfortunately the R3
// intermediate cert will expire in September 2025, but we may have our own testserver by then.
const val LETS_ENCRYPT_ISRG_X1_ROOT_PK_SHA256 = "NYbU7PBwV4y9J67c4guWTki8FJ+uudrXL0a4V4aRcrg="
const val LETS_ENCRYPT_R3_INTERM_PK_SHA256 = "jQJTbIh0grw0/1TkHSumWb+Fs0Ggogr621gT3PvPKG0="

@Suppress("UNUSED_PARAMETER")
@DelicateCoroutinesApi
class MainActivity : AppCompatActivity() {

    private var flutterEngine: FlutterEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        TrustKit.initializeWithNetworkSecurityConfiguration(this@MainActivity)

        // Appmattus global setup:
        installCertificateTransparencyProvider {
            // Match only our single Appmattus test domain:
            -"*.httptoolkit.tech"
            -"*.badssl.com"
            +"rsa4096.badssl.com"
        }

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

    fun sendUnpinned(view: View) {
        GlobalScope.launch(Dispatchers.IO) {
            onStart(R.id.unpinned)
            try {
                val mURL = URL("https://amiusing.httptoolkit.tech")
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
        val webView = WebView(this@MainActivity)

        var connectionFailed = false

        webView.loadUrl("https://amiusing.httptoolkit.tech")
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

            override fun onPageFinished(view: WebView?, url: String?) {
                if (connectionFailed) return

                println("Unpinned WebView loaded OK")
                onSuccess(R.id.webview_unpinned)
            }
        }
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
                val mURL = URL("https://sha256.badssl.com")
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
            val caStream = BufferedInputStream(resources.openRawResource(R.raw.lets_encrypt_isrg_root))
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

                val mURL = URL("https://ecc384.badssl.com")
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
                val hostname = "ecc384.badssl.com"
                val certificatePinner = CertificatePinner.Builder()
                    .add(hostname, "sha256/${LETS_ENCRYPT_ISRG_X1_ROOT_PK_SHA256}")
                    .add(hostname, "sha256/${LETS_ENCRYPT_R3_INTERM_PK_SHA256}")
                    .build()

                val client = OkHttpClient.Builder()
                    .certificatePinner(certificatePinner)
                    .build()
                val request = Request.Builder()
                    .url("https://ecc384.badssl.com")
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
            val caStream = BufferedInputStream(resources.openRawResource(R.raw.lets_encrypt_isrg_root))
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
                "https://ecc384.badssl.com",
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
                val mURL = URL("https://sha256.badssl.com")
                with(mURL.openConnection() as HttpsURLConnection) {
                    this.sslSocketFactory = TrustKit.getInstance().getSSLSocketFactory(
                            "sha256.badssl.com"
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
                val mURL = URL("https://sha256.badssl.com")
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
                    .url("https://sha256.badssl.com")
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

            val cf = CertificateFactory.getInstance("X.509")
            val caStream = BufferedInputStream(resources.openRawResource(R.raw.lets_encrypt_isrg_root))
            val caCertificate = cf.generateCertificate(caStream)

            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            keyStore.load(null)
            keyStore.setCertificateEntry("ca", caCertificate)

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

                val mURL = URL("https://ecc384.badssl.com")
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

    // Pinned by global setup from installCertificateTransparencyProvider
    // call in onCreate():
    fun sendAppmattusCTWebView(view: View) {
        onStart(R.id.appmattus_webview_ct_checked)
        val webView = WebView(this@MainActivity)

        var connectionFailed = false

        webView.loadUrl("https://rsa4096.badssl.com")
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                println("Appmattus webview SSL error: " + error.toString())
                onError(R.id.appmattus_webview_ct_checked, error.toString())
                connectionFailed = true
                handler?.cancel()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                println("Appmattus webview error: " + error.toString())
                onError(R.id.appmattus_webview_ct_checked, error.toString())
                connectionFailed = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (connectionFailed) return

                println("Appmattus WebView loaded OK")
                onSuccess(R.id.appmattus_webview_ct_checked)
            }
        }
    }

    fun sendFlutterRequest(view: View) {
        onStart(R.id.flutter_request)

        val channel = MethodChannel(flutterEngine!!.dartExecutor.binaryMessenger, "tech.httptoolkit.pinning_demo.flutter_channel")

        println("Calling Dart method from Kotlin...")
        channel.invokeMethod("sendRequest", "https://ecc384.badssl.com/", object : MethodChannel.Result {
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

                val socket = context.socketFactory.createSocket("ecc384.badssl.com", 443) as SSLSocket

                val certs = socket.session.peerCertificates

                if (!certs.any { cert ->
                        doesCertMatchPin(LETS_ENCRYPT_ISRG_X1_ROOT_PK_SHA256, cert) ||
                        doesCertMatchPin(LETS_ENCRYPT_R3_INTERM_PK_SHA256, cert)
                }) {
                    socket.close() // Close the socket immediately without sending a request
                    throw Error("Unrecognized cert hash.")
                }

                // Send a real request, just to make it clear that we trust the connection:
                val pw = PrintWriter(socket.outputStream)
                pw.println("GET / HTTP/1.1")
                pw.println("Host: ecc384.badssl.com")
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