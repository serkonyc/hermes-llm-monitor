// Hermes LLM Monitor — single-file Android app (Kotlin, no Gradle).
// Demo-first: works fully offline from baked-in sample data; live mlx-serve metrics
// (127.0.0.1 via pcdroid tunnel, then LAN) are shown as a live overlay when reachable.
// Adaptive layout: one column on cover screen, two on inner foldable screen.
// Checks GitHub Releases for updates and installs the new APK.
@file:Suppress("PackageDirectoryMismatch")

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

private const val ENDPOINT_LOOPBACK = "http://127.0.0.1:18422"
private const val ENDPOINT_LAN = "http://192.168.1.96:18422"
private const val REPO = "serkonyc/hermes-llm-monitor"
private const val APP_VERSION = "1.0.2"
private const val REFRESH_SECONDS = 10L

class MonitorActivity : Activity() {
    private val pool = Executors.newCachedThreadPool()
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var refreshButton: Button
    private lateinit var root: LinearLayout
    private lateinit var statusScroll: ScrollView
    private val updateSlot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private var updateButton: Button? = null
    private var lastLiveText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        titleView = TextView(this).apply {
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            text = "Hermes LLM Monitor"
            gravity = Gravity.CENTER_HORIZONTAL
        }
        statusView = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFFDDDDDD.toInt())
            setLineSpacing(6f, 1.1f)
            setPadding(48, 24, 48, 24)
        }
        refreshButton = Button(this).apply {
            text = "Refresh"
            setOnClickListener { refreshAll() }
        }

        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 40, 24, 24)
            addView(titleView)
            addView(refreshButton)
            addView(updateSlot)
        }
        statusScroll = ScrollView(this).apply {
            setPadding(24, 40, 24, 24)
            addView(statusView)
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(titleColumn, LinearLayout.LayoutParams(0, -1, 1f))
        }
        setContentView(root)
        applyWide(resources.configuration.screenWidthDp >= WindowLayout.MIN_INNER_WIDTH_DP)
        refreshAll()
    }

    // Two columns when the window is wide (Fold 8 inner 4:3 screen, >= 560dp).
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyWide(resources.configuration.screenWidthDp >= WindowLayout.MIN_INNER_WIDTH_DP)
    }

    private fun applyWide(wide: Boolean) {
        if (wide) {
            statusScroll.parent?.let { (it as LinearLayout).removeView(statusScroll) }
            root.addView(statusScroll, LinearLayout.LayoutParams(0, -1, 1.4f))
        } else {
            statusScroll.parent?.let { (it as LinearLayout).removeView(statusScroll) }
            root.addView(statusScroll, LinearLayout.LayoutParams(-1, -1, 1f))
        }
        statusView.textSize = if (wide) 20f else 15f
        titleView.textSize = if (wide) 30f else 22f
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    private fun refreshAll() {
        refreshButton.isEnabled = false
        pool.execute {
            val live = tryLive()
            val text = if (live != null) {
                lastLiveText = live
                live
            } else {
                demoText(lastLiveText)
            }
            runOnUiThread {
                statusView.text = text
                refreshButton.isEnabled = true
                statusView.postDelayed({ if (!isFinishing) refreshAll() }, REFRESH_SECONDS * 1000)
            }
        }
        checkForUpdate()
    }

    private fun tryLive(): String? {
        val t0 = System.currentTimeMillis()
        val base = listOf(ENDPOINT_LOOPBACK, ENDPOINT_LAN).firstNotNullOfOrNull { ep ->
            try {
                httpGet("$ep/v1/models"); ep
            } catch (_: Exception) {
                null
            }
        } ?: return null
        return try {
            val latency = System.currentTimeMillis() - t0
            val sb = StringBuilder()
            sb.appendLine("Status: ONLINE  (${latency} ms)")
            sb.appendLine("Endpoint: $base")
            sb.appendLine()
            val arr = JSONObject(httpGet("$base/v1/models")).optJSONArray("data") ?: JSONArray()
            sb.appendLine("Models: ${arr.length()}")
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                sb.appendLine("  - ${m.optString("id")}  [${m.optString("state")}]  ctx ${m.optInt("context_length")}")
            }
            sb.appendLine()
            try {
                val metrics = parseMetrics(httpGet("$base/metrics"))
                sb.appendLine("Live metrics:")
                metrics["vllm:request_success_total"]?.let { sb.appendLine("  requests: ${num(it)}") }
                metrics["vllm:generation_tokens_total"]?.let { sb.appendLine("  generated tokens: ${fmt(it.toDouble().toLong())}") }
                metrics["vllm:num_requests_running"]?.let { sb.appendLine("  running now: ${num(it)}") }
                metrics["mlx_serve:memory_mb"]?.let { sb.appendLine("  server memory: ${num(it)} MB") }
                metrics["mlx_serve:gpu_utilization_pct"]?.let { sb.appendLine("  GPU: ${num(it)}%") }
            } catch (_: Exception) {
                sb.appendLine("(metrics endpoint unavailable)")
            }
            stamp(sb)
            sb.toString()
        } catch (_: Exception) {
            null
        }
    }

    // Offline demo: realistic mlx-serve dashboard snapshot.
    private fun demoText(lastLive: String?): String {
        val sb = StringBuilder()
        sb.appendLine("OFFLINE DEMO (tap Refresh to retry live)")
        if (lastLive != null) {
            sb.appendLine("last live snapshot:")
            sb.append(lastLive.trimEnd().lines().take(4).joinToString("\n") { "  $it" })
            sb.appendLine()
        }
        sb.appendLine("Status: OFFLINE")
        sb.appendLine("Tried: $ENDPOINT_LOOPBACK (tunnel), $ENDPOINT_LAN (LAN)")
        sb.appendLine()
        sb.appendLine("Models: 2  (snapshot 21.10 11:30)")
        sb.appendLine("  - Qwen3.8-Flash  [ready]  ctx 262144")
        sb.appendLine("  - Qwen3.6-35B-A3B-4bit  [ready]  ctx 16384")
        sb.appendLine()
        sb.appendLine("Live metrics (snapshot):")
        sb.appendLine("  requests: 499")
        sb.appendLine("  generated tokens: 122 643")
        sb.appendLine("  prompt tokens: 34 212 497")
        sb.appendLine("  prefix cache hit rate: 58.8%")
        sb.appendLine("  running now: 1   waiting: 0")
        sb.appendLine("  server memory: 75 644 MB")
        sb.appendLine("  GPU: 88%")
        sb.appendLine("  uptime: 4h 52m")
        sb.appendLine()
        stamp(sb)
        return sb.toString()
    }

    private fun stamp(sb: StringBuilder) {
        sb.appendLine("Last refresh: ${java.text.DateFormat.getTimeInstance().format(System.currentTimeMillis())} (auto ${REFRESH_SECONDS}s)")
    }

    private fun parseMetrics(body: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (line in body.lineSequence()) {
            if (line.startsWith("#") || !line.contains(' ')) continue
            val key = line.substringBefore(' ')
            if (!out.containsKey(key)) out[key] = line.substringAfter(' ').trim()
        }
        return out
    }

    private fun num(s: String): String = try {
        fmt(s.toDouble().toLong())
    } catch (_: Exception) {
        s
    }

    private fun fmt(n: Long): String = String.format("%,d", n).replace(',', ' ')

    private fun checkForUpdate() {
        pool.execute {
            try {
                val latest = JSONObject(httpGet("https://api.github.com/repos/$REPO/releases/latest"))
                    .optString("tag_name").removePrefix("v")
                if (latest.isNotBlank() && latest != APP_VERSION) {
                    val url = latestDownloadUrl(latest)
                    runOnUiThread { showUpdateButton("Update to $latest", url) }
                } else {
                    runOnUiThread { updateSlot.removeView(updateButton); updateButton = null }
                }
            } catch (_: Exception) { /* no internet or no release yet */ }
        }
    }

    private fun latestDownloadUrl(tag: String): String? {
        val body = JSONObject(httpGet("https://api.github.com/repos/$REPO/releases/tags/v$tag"))
        val assets = body.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) return a.optString("browser_download_url")
        }
        return null
    }

    private fun showUpdateButton(label: String, apkUrl: String?) {
        if (updateButton == null) {
            updateButton = Button(this)
            updateSlot.addView(updateButton, LinearLayout.LayoutParams(-2, -2))
        }
        updateButton?.text = label
        updateButton?.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).setData(Uri.parse("package:$packageName")))
                return@setOnClickListener
            }
            if (apkUrl != null) openInBrowser(apkUrl)
        }
    }

    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
        }
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000
            readTimeout = 5000
            setRequestProperty("User-Agent", "llm-monitor")
        }
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

// WindowLayout documents the fold adaptation; used by Activity and kept separate for clarity.
object WindowLayout {
    // Fold 8 inner screen is 4:3 (1848x2448), cover is 10:16 (1248x1972).
    // Width decides: inner portrait ~640dp, cover ~430dp.
    const val MIN_INNER_WIDTH_DP = 560
}

fun isWide(screenWidthDp: Int): Boolean = screenWidthDp >= WindowLayout.MIN_INNER_WIDTH_DP
