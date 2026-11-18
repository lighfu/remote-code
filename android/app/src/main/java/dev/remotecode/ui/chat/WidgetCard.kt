package dev.remotecode.ui.chat

import dev.remotecode.R
import androidx.compose.ui.res.stringResource

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.remotecode.data.Entry

/** HTML/SVG はファイルに触れず、送信は直前のタッチ時のみ許可する。 */
@Composable
fun WidgetCard(e: Entry) {
    val html = e.html ?: return
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val css = remember(dark, scheme) { widgetCss(dark, scheme) }
    val page = remember(html, css) { widgetPage(html, css) }
    val reply by rememberUpdatedState(LocalNextStep.current?.reply)
    var heightDp by rememberSaveable(e.id) { mutableIntStateOf(0) }

    Card(
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 2.dp, bottom = 6.dp)) {
                Icon(Icons.Rounded.Widgets, null, Modifier.size(16.dp), tint = scheme.primary)
                Spacer(Modifier.size(6.dp))
                Text(
                    e.title ?: stringResource(R.string.chat_widget_card_001),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AndroidView(
                factory = { ctx -> widgetView(ctx, onHeight = { heightDp = it }, onPrompt = { t -> reply?.invoke(t) }) },
                update = { v ->
                    if (v.tag != page) {
                        v.tag = page
                        v.loadDataWithBaseURL("https://widget.remotecode.invalid/", page, "text/html", "utf-8", null)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(if (heightDp > 0) heightDp.dp else 160.dp),
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun widgetView(ctx: android.content.Context, onHeight: (Int) -> Unit, onPrompt: (String) -> Unit): WebView {
    val main = Handler(Looper.getMainLooper())
    var lastTouch = 0L
    val web = WebView(ctx)
    web.setBackgroundColor(AColor.TRANSPARENT)
    web.isVerticalScrollBarEnabled = false
    web.settings.apply {
        javaScriptEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        domStorageEnabled = false
        setSupportZoom(false)
    }
    web.setOnTouchListener { _, ev ->
        if (ev.action == MotionEvent.ACTION_UP) lastTouch = SystemClock.uptimeMillis()
        false
    }
    web.addJavascriptInterface(object {
        @JavascriptInterface
        fun height(px: Float) {
            val h = px.toInt().coerceIn(40, 4000)
            main.post { onHeight(h) }
        }

        @JavascriptInterface
        fun prompt(text: String) {
            if (SystemClock.uptimeMillis() - lastTouch > 1500) return
            val t = text.trim().take(4000)
            if (t.isNotEmpty()) main.post { onPrompt(t) }
        }

        @JavascriptInterface
        fun open(url: String) {
            if (SystemClock.uptimeMillis() - lastTouch > 1500) return
            main.post { openExternal(ctx, url) }
        }
    }, "RC")
    web.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            openExternal(ctx, request.url.toString())
            return true
        }
    }
    return web
}

private fun openExternal(ctx: android.content.Context, url: String) {
    if (!url.startsWith("https://") && !url.startsWith("http://")) return
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun hex(c: Color): String = "#%06X".format(c.toArgb() and 0xFFFFFF)

/** 明るい順に 50, 100, 200, 400, 600, 800, 900。 */
private val RAMPS = mapOf(
    "purple" to listOf("#EEEDFE", "#CECBF6", "#AFA9EC", "#7F77DD", "#534AB7", "#3C3489", "#26215C"),
    "teal" to listOf("#E1F5EE", "#9FE1CB", "#5DCAA5", "#1D9E75", "#0F6E56", "#085041", "#04342C"),
    "coral" to listOf("#FAECE7", "#F5C4B3", "#F0997B", "#D85A30", "#993C1D", "#712B13", "#4A1B0C"),
    "pink" to listOf("#FBEAF0", "#F4C0D1", "#ED93B1", "#D4537E", "#993556", "#72243E", "#4B1528"),
    "gray" to listOf("#F1EFE8", "#D3D1C7", "#B4B2A9", "#888780", "#5F5E5A", "#444441", "#2C2C2A"),
    "blue" to listOf("#E6F1FB", "#B5D4F4", "#85B7EB", "#378ADD", "#185FA5", "#0C447C", "#042C53"),
    "green" to listOf("#EAF3DE", "#C0DD97", "#97C459", "#639922", "#3B6D11", "#27500A", "#173404"),
    "amber" to listOf("#FAEEDA", "#FAC775", "#EF9F27", "#BA7517", "#854F0B", "#633806", "#412402"),
    "red" to listOf("#FCEBEB", "#F7C1C1", "#F09595", "#E24B4A", "#A32D2D", "#791F1F", "#501313"),
)

/** claude.ai 互換の CSS 変数・クラスを定義する。 */
private fun widgetCss(dark: Boolean, s: androidx.compose.material3.ColorScheme): String {
    val vars = """
        :root{color-scheme:${if (dark) "dark" else "light"};
        --text-primary:${hex(s.onSurface)};--text-secondary:${hex(s.onSurfaceVariant)};--text-muted:${hex(s.outline)};--text-disabled:${hex(s.outline)};
        --surface-0:${hex(s.surfaceContainerLow)};--surface-1:${hex(s.surfaceContainer)};--surface-2:${hex(s.surfaceContainerHigh)};--surface-3:${hex(s.surfaceContainerHighest)};
        --surface-panel:${hex(s.surfaceContainerHigh)};--surface-popover:${hex(s.surfaceContainerHighest)};
        --border:${hex(s.outlineVariant)};--border-strong:${hex(s.outline)};--border-stronger:${hex(s.onSurfaceVariant)};
        --text-accent:${hex(s.primary)};--bg-accent:${hex(s.primaryContainer)};--border-accent:${hex(s.primary)};--fill-accent:${hex(s.primary)};--on-accent:${hex(s.onPrimary)};
        --text-danger:${hex(s.error)};--bg-danger:${hex(s.errorContainer)};--border-danger:${hex(s.error)};
        --text-success:${if (dark) "#97C459" else "#3B6D11"};--bg-success:${if (dark) "#27500A" else "#EAF3DE"};--border-success:${if (dark) "#639922" else "#97C459"};
        --text-warning:${if (dark) "#EF9F27" else "#854F0B"};--bg-warning:${if (dark) "#633806" else "#FAEEDA"};--border-warning:${if (dark) "#BA7517" else "#EF9F27"};
        --font-sans:sans-serif;--font-mono:monospace;--font-voice:serif;
        --radius:8px;--pad-sm:6px;--pad-md:10px;--pad-lg:14px;--pad-xl:20px;--gap-xs:4px;--gap-sm:8px;--gap-md:12px;--gap-lg:16px;--gap-xl:24px}
    """.trimIndent()
    val ramps = StringBuilder()
    for ((name, r) in RAMPS) {
        val fill = if (dark) r[5] else r[0]
        val stroke = if (dark) r[2] else r[4]
        val title = if (dark) r[1] else r[5]
        val sub = if (dark) r[2] else r[4]
        ramps.append(".c-$name>rect,.c-$name>circle,.c-$name>ellipse,.c-$name>polygon,rect.c-$name,circle.c-$name,ellipse.c-$name{fill:$fill;stroke:$stroke}")
        ramps.append(".c-$name .t,.c-$name .th,text.c-$name{fill:$title}.c-$name .ts{fill:$sub}")
    }
    return vars + """
        html,body{margin:0;padding:0;background:transparent;color:var(--text-primary);font-family:var(--font-sans);font-size:15px;line-height:1.6;overflow:hidden}
        #rc-root{overflow-x:auto}
        *{box-sizing:border-box}
        svg{max-width:100%;height:auto}
        img,canvas{max-width:100%}
        h1{font-size:20px;font-weight:500}h2{font-size:17px;font-weight:500}h3{font-size:15px;font-weight:500}
        a{color:var(--text-accent)}
        .sr-only{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap}
        .t{fill:var(--text-primary);font-size:14px}.ts{fill:var(--text-secondary);font-size:12px}.th{fill:var(--text-primary);font-size:14px;font-weight:500}
        button{font:inherit;font-size:14px;color:var(--text-primary);background:transparent;border:0.5px solid var(--border-strong);border-radius:var(--radius);padding:6px 12px;min-height:36px}
        button:active{transform:scale(.98)}
        input,select,textarea{font:inherit;color:var(--text-primary);background:var(--surface-1);border:0.5px solid var(--border-strong);border-radius:var(--radius);padding:6px 10px}
        input[type=range]{padding:0;width:100%}
        table{border-collapse:collapse}
    """.trimIndent() + ramps
}

private fun widgetPage(code: String, css: String): String = """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/@tabler/icons-webfont@3.19.0/dist/tabler-icons.min.css">
<style>$css</style>
<script>
window.sendPrompt=function(t){try{RC.prompt(String(t))}catch(e){}};
window.openLink=function(u){try{RC.open(String(u))}catch(e){}};
</script></head><body><div id="rc-root">$code</div>
<script>
(function(){var r=document.getElementById('rc-root');var last=0;
function report(){var h=Math.ceil(r.getBoundingClientRect().height)+2;if(Math.abs(h-last)>1){last=h;try{RC.height(h)}catch(e){}}}
if(window.ResizeObserver)new ResizeObserver(report).observe(r);window.addEventListener('load',report);setTimeout(report,50);setTimeout(report,600);})();
</script></body></html>
"""
