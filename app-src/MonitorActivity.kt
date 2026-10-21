// Hermes LLM Monitor — single-file Android app (Kotlin, no Gradle).
// Shows mlx-serve health, /v1/models, live usage via /usage and /completion probe.
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
import kotlin.concurrent.thread
import kotlin.math.roundToInt

private const val ENDPOINT_LAN = "http://192.168.1.96:18422"
private const val ENDPOINT_LOOPBACK = "http://127.0.0.1:18422"
private const val REPO = "serkonyc/hermes-llm-monitor"
private const val APP_VERSION = "1.0.0"
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
            text = "Loading..."
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
            val text = try {
                collectStatus()
            } catch (e: Exception) {
                "Server unreachable.\n$ENDPOINT_LAN (LAN) / $ENDPOINT_LOOPBACK (tunnel)\n${e.message}"
            }
            runOnUiThread {
                statusView.text = text
                refreshButton.isEnabled = true
                // Re-check periodically.
                statusView.postDelayed({ if (!isFinishing) refreshAll() }, REFRESH_SECONDS * 1000)
            }
        }
        checkForUpdate()
    }

    private fun collectStatus(): String {
        val sb = StringBuilder()
        val t0 = System.currentTimeMillis()
        // Loopback first: mlx-serve binds 127.0.0.1; Termux/PCDroid tunnels it to the phone.
        val (resp, base) = listOf(ENDPOINT_LAN, ENDPOINT_LOOPBACK).firstNotNullOfOrNull { ep ->
            try {
                httpGet("$ep/v1/models") to ep
            } catch (_: Exception) {
                null
            }
        } ?: return "Server unreachable.\n$ENDPOINT_LAN (LAN)\n$ENDPOINT_LOOPBACK (tunnel)\nStart the pcdroid tunnel: adb reverse-style bridge or Termux port-fwd."
        val latency = System.currentTimeMillis() - t0
        val models = resp
        val arr = JSONObject(models).optJSONArray("data") ?: JSONArray()
        sb.appendLine("Status: ONLINE  (${latency} ms)")
        sb.appendLine("Endpoint: $base")
        sb.appendLine()
        sb.appendLine("Models: ${arr.length()}")
        for (i in 0 until arr.length()) {
            sb.appendLine("  - " + arr.getJSONObject(i).optString("id"))
        }
        sb.appendLine()
        // Live usage if the server exposes it (hermes/usage extension); silent if not.
        try {
            val usage = httpGet("$base/usage")
            sb.appendLine("Usage:")
            sb.appendLine(pretty(usage))
        } catch (_: Exception) {
            sb.appendLine("Usage: endpoint not available")
        }
        sb.appendLine()
        sb.appendLine("Last refresh: ${java.text.DateFormat.getTimeInstance().format(System.currentTimeMillis())} (auto ${REFRESH_SECONDS}s)")
        return sb.toString()
    }

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
            connectTimeout = 5000
            readTimeout = 8000
            setRequestProperty("User-Agent", "llm-monitor")
        }
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun pretty(json: String): String {
        return try {
            JSONObject(json).toString(2).take(1500)
        } catch (_: Exception) {
            json.take(600)
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
