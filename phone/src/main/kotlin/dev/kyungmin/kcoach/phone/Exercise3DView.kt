package dev.kyungmin.kcoach.phone

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject
import kotlin.math.abs

/**
 * Plays the machine-viewer 3D rep for one exercise from the bundled assets/viewer3d page (three.js,
 * no network). One finger drag turns the camera; a mostly vertical drag is left to the page scroll.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class Exercise3DView(context: Context, exerciseId: String) : FrameLayout(context) {
    private val web = WebView(context)
    private var downX = 0f
    private var downY = 0f

    init {
        web.setBackgroundColor(Color.rgb(52, 50, 46))
        web.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
        }
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.url.host != HOST) return null
                val path = request.url.path.orEmpty().trimStart('/')
                return runCatching {
                    WebResourceResponse(mimeOf(path), null, context.assets.open("viewer3d/$path"))
                }.getOrElse { WebResourceResponse("text/plain", null, 404, "Not Found", emptyMap(), "".byteInputStream()) }
            }
        }
        web.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
                MotionEvent.ACTION_MOVE -> if (abs(e.x - downX) > abs(e.y - downY)) v.parent?.requestDisallowInterceptTouchEvent(true)
            }
            false
        }
        addView(web, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web.loadUrl("https://$HOST/index.html?ex=$exerciseId")
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeView(web)
        web.destroy()
    }

    companion object {
        private const val HOST = "app3d.local"

        private fun mimeOf(path: String) = when (path.substringAfterLast('.', "")) {
            "html" -> "text/html"
            "js" -> "text/javascript"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }

        private var ids: Set<String>? = null

        /** Exercises that have a bundled 3D model. */
        fun has(context: Context, exerciseId: String): Boolean {
            val known = ids ?: runCatching {
                JSONObject(context.assets.open("viewer3d/exercise_models.json").bufferedReader().use { it.readText() })
                    .keys().asSequence().toSet()
            }.getOrDefault(emptySet()).also { ids = it }
            return exerciseId in known
        }
    }
}
