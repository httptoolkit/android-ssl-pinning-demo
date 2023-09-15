package tech.httptoolkit.pinning_demo

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.android.volley.RequestQueue
import com.android.volley.toolbox.*
import com.datatheorem.android.trustkit.TrustKit
import kotlinx.coroutines.*
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

const val LETS_ENCRYPT_ROOT_SHA256 = "C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M="

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        TrustKit.initializeWithNetworkSecurityConfiguration(this@MainActivity)
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

            val duration = Toast.LENGTH_LONG
            val toast = Toast.makeText(this@MainActivity, message, duration)
            toast.show()
        }
    }

    fun sendUnpinned(view: View) {
        GlobalScope.launch(Dispatchers.IO) {
            onStart(R.id.unpinned)
            try {
                val mURL = URL("https://httptoolkit.com")
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

    fun sendOkHttpPinned(view: View) {
        GlobalScope.launch(Dispatchers.IO) {
            onStart(R.id.okhttp_pinned)

            try {
                val hostname = "sha256.badssl.com"
                val certificatePinner = CertificatePinner.Builder()
                    .add(hostname, "sha256/${LETS_ENCRYPT_ROOT_SHA256}")
                    .build()

                val client = OkHttpClient.Builder()
                    .certificatePinner(certificatePinner)
                    .build()
                val request = Request.Builder()
                    .url("https://sha256.badssl.com")
                    .build();

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
                "https://sha256.badssl.com",
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

    fun sendManuallyCustomPinned(view: View) {
        GlobalScope.launch(Dispatchers.IO) {
            onStart(R.id.manually_pinned)
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

                val socket = context.socketFactory.createSocket("sha256.badssl.com", 443) as SSLSocket

                val certs = socket.session.peerCertificates

                if (!certs.any { cert -> doesCertMatchPin(LETS_ENCRYPT_ROOT_SHA256, cert) }) {
                    socket.close() // Close the socket immediately without sending a request
                    throw Error("Unrecognized cert hash.")
                }

                // Send a real request, just to make it clear that we trust the connection:
                val pw = PrintWriter(socket.outputStream)
                pw.println("GET / HTTP/1.1")
                pw.println("Host: sha256.badssl.com")
                pw.println("")
                pw.flush()

                val br = BufferedReader(InputStreamReader(socket.inputStream))
                val responseLine = br.readLine()

                println("Response was: $responseLine")
                socket.close()

                onSuccess(R.id.manually_pinned)
            } catch (e: Throwable) {
                println(e)
                onError(R.id.manually_pinned, e.toString())
            }
        }
    }

    private fun doesCertMatchPin(pin: String, cert: Certificate): Boolean {
        val certHash = cert.publicKey.encoded.toByteString().sha256()
        return certHash == pin.decodeBase64()
    }
}