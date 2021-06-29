package tech.httptoolkit.pinning_demo

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL


class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    private suspend fun onStart(@IdRes id: Int) {
        withContext(Dispatchers.Main) {
            val button = findViewById<Button>(id)
            button.setBackgroundColor(
                ContextCompat.getColor(this@MainActivity, R.color.purple_500)
            )
            button.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
        }
    }

    private suspend fun onSuccess(@IdRes id: Int) {
        withContext(Dispatchers.Main) {
            val button = findViewById<Button>(id)
            button.setBackgroundColor(
                    ContextCompat.getColor(this@MainActivity, R.color.success)
            )
            val img: Drawable = ContextCompat.getDrawable(this@MainActivity,
                    R.drawable.baseline_check_circle_24
            )!!
            button.setCompoundDrawablesWithIntrinsicBounds(img, null, null, null)
        }
    }

    private suspend fun onError(@IdRes id: Int, message: String) {
        withContext(Dispatchers.Main) {
            val button = findViewById<Button>(id)
            button.setBackgroundColor(
                    ContextCompat.getColor(this@MainActivity, R.color.failure)
            )
            val img: Drawable = ContextCompat.getDrawable(this@MainActivity,
                R.drawable.baseline_cancel_24
            )!!
            button.setCompoundDrawablesWithIntrinsicBounds(img, null, null, null)

            val duration = Toast.LENGTH_SHORT
            val toast = Toast.makeText(applicationContext, message, duration)
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
}