package tech.httptoolkit.pinning_demo

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.android.volley.toolbox.HurlStack
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import kotlinx.coroutines.*
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory


class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
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
                val mURL = URL("https://badssl.com")
                with(mURL.openConnection() as HttpURLConnection) {
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
                // Untrusted in system store, trusted & pinned in network config:
                val mURL = URL("https://untrusted-root.badssl.com")
                with(mURL.openConnection() as HttpURLConnection) {
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
                val hostname = "badssl.com"
                val certificatePinner = CertificatePinner.Builder()
                    // DigiCert SHA2 Secure Server CA (valid until March 2023)
                    .add(hostname, "sha256/5kJvNEMw0KjrCAu7eXY5HZdvyCS13BbA0VJG1RSP91w=")
                    .build()

                val client = OkHttpClient.Builder()
                    .certificatePinner(certificatePinner)
                    .build()
                val request = Request.Builder()
                    .url("https://badssl.com")
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
            val caStream = BufferedInputStream(resources.openRawResource(R.raw.example_com_digicert_ca))
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

            val requestQueue = Volley.newRequestQueue(this@MainActivity,
                HurlStack(null, context.socketFactory)
            )

            // Make a request using that client:
            val stringRequest = StringRequest(
                com.android.volley.Request.Method.GET,
                "https://example.com",
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
}