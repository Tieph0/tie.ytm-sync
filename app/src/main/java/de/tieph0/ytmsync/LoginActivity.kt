package de.tieph0.ytmsync

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** Google sign-in in a WebView; grabs the music.youtube.com cookie once logged in. */
class LoginActivity : ComponentActivity() {

    companion object {
        private const val LOGIN_URL =
            "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true" +
                "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue" +
                "%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F"
        private const val YTM = "https://music.youtube.com"
    }

    private var done = false

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)

        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            cookies.setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (done || !url.startsWith(YTM)) return
                    cookies.flush()
                    val c = cookies.getCookie(YTM)
                    if (YtmClient.cookieLooksValid(c)) {
                        done = true
                        Prefs(this@LoginActivity).cookie = c
                        setResult(Activity.RESULT_OK)
                        finish()
                    }
                }
            }
        }

        onBackPressedDispatcher.addCallback(this) {
            if (web.canGoBack()) web.goBack() else finish()
        }

        // Start from a clean session so the right account gets picked.
        cookies.removeAllCookies { web.loadUrl(LOGIN_URL) }

        setContent {
            AppTheme {
                Scaffold(topBar = { TopAppBar(title = { Text("Bei Google anmelden") }) }) { pad ->
                    AndroidView(factory = { web }, modifier = Modifier.padding(pad).fillMaxSize())
                }
            }
        }
    }
}
