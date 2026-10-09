package com.example.song.ui.components

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.song.data.crawler.SpotifyCrawlPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyCrawlerWebView(
    targetUrl: String,
    pipeline: SpotifyCrawlPipeline,
    isCrawling: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context as? Activity

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isDestroyed by remember { mutableStateOf(false) }
    var lastInjectedUrl by remember { mutableStateOf<String?>(null) }

    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var graceTimerRunnable by remember { mutableStateOf<Runnable?>(null) }

    // Screen protection: FLAG_KEEP_SCREEN_ON strictly when CRAWLING (PRD §5.1)
    DisposableEffect(isCrawling, activity) {
        if (activity != null && isCrawling) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (activity != null) {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    val scriptContent = remember(context) {
        try {
            context.assets.open("scripts/spotify_spider.js").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).readText()
            }
        } catch (e: Exception) {
            Log.e("SpotifyCrawlerWebView", "Failed to read spotify_spider.js asset", e)
            ""
        }
    }

    fun destroyWebViewSafely(wv: WebView?) {
        if (wv == null || isDestroyed) return
        isDestroyed = true
        try {
            wv.evaluateJavascript("window.__spider?.stop?.();", null)
            wv.stopLoading()
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.webViewClient = WebViewClient()
            wv.webChromeClient = WebChromeClient()
            wv.loadUrl("about:blank")
            wv.clearHistory()
            wv.clearCache(true)
            wv.clearFormData()
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            wv.destroy()
            Log.d("SpotifyCrawlerWebView", "WebView teardown completed cleanly")
        } catch (e: Exception) {
            Log.e("SpotifyCrawlerWebView", "Error tearing down WebView", e)
        }
    }

    // Lifecycle Observer: ON_STOP grace window (5s timer per PRD §5.1)
    DisposableEffect(lifecycleOwner, webViewRef) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    Log.d("SpotifyCrawlerWebView", "App backgrounded (ON_STOP), starting 5s teardown grace window")
                    // Notify JS to pause
                    webViewRef?.evaluateJavascript("window.__spider?.stop?.();", null)

                    val runnable = Runnable {
                        Log.d("SpotifyCrawlerWebView", "5s grace timer expired, performing clean WebView disposal")
                        destroyWebViewSafely(webViewRef)
                    }
                    graceTimerRunnable = runnable
                    mainHandler.postDelayed(runnable, 5000)
                }
                Lifecycle.Event.ON_START -> {
                    // Cancel grace timer if app returned
                    graceTimerRunnable?.let {
                        mainHandler.removeCallbacks(it)
                        graceTimerRunnable = null
                        Log.d("SpotifyCrawlerWebView", "App restored to foreground, cancelled 5s teardown grace timer")
                    }
                }
                Lifecycle.Event.ON_DESTROY -> {
                    destroyWebViewSafely(webViewRef)
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            graceTimerRunnable?.let { mainHandler.removeCallbacks(it) }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewRef = this
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    // Hardened Settings for Desktop Spotify (PRD §6.2 & §6.3)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        @Suppress("DEPRECATION")
                        databaseEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        @Suppress("DEPRECATION")
                        allowFileAccessFromFileURLs = false
                        @Suppress("DEPRECATION")
                        allowUniversalAccessFromFileURLs = false
                        setGeolocationEnabled(false)
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        setSupportMultipleWindows(false)

                        // Pure Desktop Chrome UA (PRD §6.3)
                        userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                    }
                    setInitialScale(50)

                    // Register WebMessageListener if supported (PRD §6.1)
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                        val allowedOrigins = setOf("https://open.spotify.com")
                        val listener = WebViewCompat.WebMessageListener { _: WebView, message: WebMessageCompat, _: Uri, _: Boolean, _: JavaScriptReplyProxy ->
                            val rawData = message.data
                            if (!rawData.isNullOrEmpty()) {
                                CoroutineScope(Dispatchers.IO).launch {
                                    pipeline.postBridgeMessage(rawData)
                                }
                            }
                        }
                        try {
                            WebViewCompat.addWebMessageListener(this, "PulseBridge", allowedOrigins, listener)
                        } catch (e: Exception) {
                            Log.e("SpotifyCrawlerWebView", "Failed adding WebMessageListener", e)
                        }
                    } else {
                        // Fallback evaluateJavascript polling (PRD §6.1)
                        val pollRunnable = object : Runnable {
                            override fun run() {
                                if (isDestroyed) return
                                evaluateJavascript(
                                    "(function(){ var q = window.__pulseQueue || []; window.__pulseQueue = []; return JSON.stringify(q); })()"
                                ) { jsonResult ->
                                    if (!jsonResult.isNullOrEmpty() && jsonResult != "null" && jsonResult != "[]") {
                                        CoroutineScope(Dispatchers.IO).launch {
                                            try {
                                                val items = com.google.gson.Gson().fromJson(jsonResult, Array<String>::class.java)
                                                for (item in items) {
                                                    pipeline.postBridgeMessage(item)
                                                }
                                            } catch (e: Exception) {
                                                Log.e("SpotifyCrawlerWebView", "Error parsing fallback queue", e)
                                            }
                                        }
                                    }
                                    mainHandler.postDelayed(this, 500)
                                }
                            }
                        }
                        mainHandler.postDelayed(pollRunnable, 500)
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val host = request?.url?.host ?: ""
                            val isAllowed = host == "open.spotify.com" ||
                                            host.endsWith(".scdn.co") ||
                                            host == "accounts.spotify.com"
                            return !isAllowed // Block if not allowed domain
                        }

                        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                            handler?.cancel() // PRD §6.2: Always cancel on SSL error
                        }

                        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                            Log.e("SpotifyCrawlerWebView", "Renderer process crashed! Guarding host process...")
                            destroyWebViewSafely(view)
                            CoroutineScope(Dispatchers.IO).launch {
                                pipeline.postBridgeMessage("""{"v":1,"type":"ERROR","code":"RENDERER_CRASH"}""")
                            }
                            return true // PRD §5.1: return true to keep host process alive
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (url != null && url.contains("open.spotify.com")) {
                                // 1. Enforce Fixed Desktop Viewport & Hide Sidebars/Overlays Without Overriding Grid Layouts
                                view?.evaluateJavascript(
                                    """
                                    (function() {
                                        var meta = document.querySelector('meta[name="viewport"]');
                                        if (!meta) {
                                            meta = document.createElement('meta');
                                            meta.name = 'viewport';
                                            document.head.appendChild(meta);
                                        }
                                        meta.content = 'width=1280, initial-scale=0.6, maximum-scale=1.0';

                                        var style = document.createElement('style');
                                        style.id = '__pulse_crawler_layout_override';
                                        style.innerHTML = `
                                            /* Hide sidebars, topbars and overlays */
                                            nav,
                                            [data-testid="left-sidebar"],
                                            .Root__nav-bar,
                                            .Root__globalNav,
                                            header[data-testid="top-bar"],
                                            #onetrust-consent-sdk,
                                            #onetrust-banner-sdk,
                                            [data-testid="signup-bar"],
                                            div[data-testid="cookie-notice"],
                                            .open-in-app,
                                            [data-testid="open-in-app-button"],
                                            #app-remote {
                                                display: none !important;
                                                width: 0 !important;
                                                height: 0 !important;
                                                position: absolute !important;
                                                left: -9999px !important;
                                                pointer-events: none !important;
                                            }
                                        `;
                                        if (!document.getElementById('__pulse_crawler_layout_override')) {
                                            document.head.appendChild(style);
                                        }

                                        var acceptBtn = document.querySelector('#onetrust-accept-btn-handler');
                                        if (acceptBtn) acceptBtn.click();
                                    })();
                                    """.trimIndent(),
                                    null
                                )

                                // 2. Guard against double script injection
                                if (scriptContent.isNotEmpty() && lastInjectedUrl != url) {
                                    lastInjectedUrl = url
                                    Log.d("SpotifyCrawlerWebView", "DOM Hydrated. Injecting spotify_spider.js...")
                                    view?.evaluateJavascript(scriptContent, null)
                                }
                            }
                        }
                    }

                    loadUrl(targetUrl)
                }
            },
            update = { wv ->
                if (wv.url != targetUrl && targetUrl.isNotBlank() && !isDestroyed) {
                    lastInjectedUrl = null
                    wv.loadUrl(targetUrl)
                }
            }
        )
    }
}
