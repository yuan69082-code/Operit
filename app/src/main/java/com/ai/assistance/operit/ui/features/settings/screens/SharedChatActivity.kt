package com.ai.assistance.operit.ui.features.settings.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.ai.assistance.operit.R

class SharedChatActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var chooser: ValueCallback<Array<Uri>>? = null
    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        chooser?.onReceiveValue(uri?.let { arrayOf(it) })
        chooser = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val address = intent.getStringExtra("url")
        val origin = address?.let(Uri::parse)
        if (origin == null || origin.scheme !in listOf("http", "https") || origin.host.isNullOrBlank() || origin.userInfo != null) {
            finish()
            return
        }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        layout.addView(Button(this).apply {
            setText(R.string.shared_chat_close)
            setOnClickListener { finish() }
        })
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // No native Javascript bridge is exposed to the remote shared service.
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url
                    if (target.scheme == origin.scheme && target.host == origin.host && target.port == origin.port) return false
                    if (target.scheme in listOf("http", "https")) startActivity(Intent(Intent.ACTION_VIEW, target))
                    return true
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) Toast.makeText(this@SharedChatActivity,
                        R.string.shared_chat_connection_failed, Toast.LENGTH_LONG).show()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>,
                    params: WebChromeClient.FileChooserParams): Boolean {
                    chooser?.onReceiveValue(null)
                    chooser = callback
                    pickFile.launch("*/*")
                    return true
                }
            }
        }
        layout.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
        })
        web.loadUrl(origin.toString())
    }

    override fun onDestroy() {
        chooser?.onReceiveValue(null)
        chooser = null
        if (::web.isInitialized) { web.stopLoading(); web.destroy() }
        super.onDestroy()
    }
}
