// Hermes LLM Monitor — single-file Android app (Kotlin, no Gradle).
// Demo-first: works fully offline from baked-in sample data; live mlx-serve metrics
// (127.0.0.1 via pcdroid tunnel, then LAN) are shown as a live overlay when reachable.
// Adaptive layout: one column on cover screen, two on inner foldable screen.
// Checks GitHub Releases for updates and installs the new APK.
package ai.serge.llmmonitor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// Fold threshold: Fold 8 cover window ~430dp, inner window ~640dp.
object WindowLayout {
    const val MIN_INNER_WIDTH_DP = 560
}

private const val ENDPOINT_LOOPBACK = "http://127.0.0.1:18422"
private const val ENDPOINT_LAN = "http://192.168.1.96:18422"
private const val REPO = "serkonyc/hermes-llm-monitor"
private const val APP_VERSION = "1.1.9-fix"
private const val REFRESH_SECONDS = 10L

class MonitorActivity : Activity() {
    private val pool = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var meter: ActivityMeterView
    private lateinit var sparkline: SparklineView
    private lateinit var galaxy: GalaxyView
    private lateinit var refreshButton: Button
    private lateinit var root: LinearLayout
    private lateinit var titleColumn: LinearLayout
    private lateinit var visualColumn: LinearLayout
    private val updateSlot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private var updateButton: Button? = null
    private var lastLiveText: String? = null

    // Shared parse state, written only on the UI thread after a fetch.
    private var liveBase: String? = null
    private var prevGenTokens: Double? = null
    private var prevTimeMs: Long? = null
    private var demoTpsIndex = 0

    // ONE Runnable seeding its own reposts — started in onResume, removed in onPause.
    private val refreshTick = object : Runnable {
        override fun run() {
            doRefresh()
            mainHandler.postDelayed(this, REFRESH_SECONDS * 1000)
        }
    }

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
        meter = ActivityMeterView(this)
        sparkline = SparklineView(this)
        galaxy = GalaxyView(this)
        refreshButton = Button(this).apply {
            text = "Refresh"
            setOnClickListener { doRefresh() }
        }

        titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 40, 24, 24)
            addView(titleView)
            addView(meter, LinearLayout.LayoutParams(-1, dp(72f)))
            addView(refreshButton)
            addView(updateSlot)
        }
        visualColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(galaxy, LinearLayout.LayoutParams(-1, dp(220f)))
            addView(sparkline, LinearLayout.LayoutParams(-1, dp(120f)))
            val scroll = ScrollView(this@MonitorActivity).apply { setPadding(0, 16, 0, 0); addView(statusView) }
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        root = LinearLayout(this).apply {
            setBackgroundColor(0xFF0D1117.toInt()) // no theme exists: force dark so light text always contrasts
            orientation = LinearLayout.VERTICAL
        }
        setContentView(root)
        applyWide(resources.configuration.screenWidthDp >= WindowLayout.MIN_INNER_WIDTH_DP)
        checkForUpdate() // once, not per refresh tick — GitHub allows 60 unauth req/hr
    }

    // Two columns when the window is wide (Fold 8 inner 4:3 screen, >= 560dp).
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyWide(newConfig.screenWidthDp >= WindowLayout.MIN_INNER_WIDTH_DP)
    }

    private fun applyWide(wide: Boolean) {
        root.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        titleColumn.parent?.let { (it as LinearLayout).removeView(titleColumn) }
        visualColumn.parent?.let { (it as LinearLayout).removeView(visualColumn) }
        if (wide) {
            root.addView(titleColumn, LinearLayout.LayoutParams(0, -1, 1f))
            root.addView(visualColumn, LinearLayout.LayoutParams(0, -1, 1.4f))
        } else {
            root.addView(titleColumn, LinearLayout.LayoutParams(-1, -2))
            root.addView(visualColumn, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        statusView.textSize = if (wide) 20f else 15f
        titleView.textSize = if (wide) 30f else 22f
    }

    override fun onResume() {
        super.onResume()
        galaxy.resumeTicker()
        doRefresh()
        mainHandler.removeCallbacks(refreshTick)
        mainHandler.post(refreshTick)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(refreshTick)
        galaxy.pauseTicker()
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun doRefresh() {
        refreshButton.isEnabled = false
        pool.execute {
            val snapshot = fetchLive()
            val text = if (snapshot != null) {
                lastLiveText = snapshot.text
                snapshot.text
            } else {
                demoText(lastLiveText)
            }
            runOnUiThread {
                if (isDestroyed()) return@runOnUiThread
                statusView.text = text
                refreshButton.isEnabled = true
                if (snapshot != null) applyLiveSnapshot(snapshot) else advanceDemo()
            }
        }
    }

    // Network part: runs on the pool thread, returns parsed values or null when offline.
    private class LiveSnapshot(
        val text: String,
        val base: String,
        val genTokens: Double?,
        val running: Int,
        val waiting: Int,
        val models: List<StarModel>,
    )

    private fun fetchLive(): LiveSnapshot? {
        val t0 = System.currentTimeMillis()
        // Avoids firstNotNullOfOrNull: its kotlin-stdlib inline call has shown up as a
        // silent blank screen on One UI (see v1.2.3-java diagnosis). Plain loop instead.
        var body: String? = null
        var base: String = ENDPOINT_LOOPBACK
        for (ep in arrayOf(ENDPOINT_LOOPBACK, ENDPOINT_LAN)) {
            try { body = httpGet("$ep/v1/models"); base = ep; break } catch (_: Exception) {}
        }
        if (body == null) return null
        return try {
            val latency = System.currentTimeMillis() - t0
            val sb = StringBuilder()
            sb.appendLine("Status: ONLINE  (${latency} ms)")
            sb.appendLine("Endpoint: $base")
            sb.appendLine()
            val arr = JSONObject(body!!).optJSONArray("data") ?: JSONArray()
            val models = ArrayList<StarModel>()
            sb.appendLine("Models: ${arr.length()}")
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val id = m.optString("id")
                val state = m.optString("state", "ready")
                models.add(StarModel(id, state, m.optDouble("bytes_resident", 0.0)))
                sb.appendLine("  - $id  [$state]  ctx ${m.optInt("context_length")}")
            }
            sb.appendLine()
            var metricsText = ""
            var genTokens: Double? = null
            var running = 0
            var waiting = 0
            try {
                metricsText = httpGet("$base/metrics")
                val metrics = parseMetrics(metricsText)
                genTokens = metrics["vllm:generation_tokens_total"]?.toDoubleOrNull()
                running = metrics["vllm:num_requests_running"]?.toDoubleOrNull()?.toInt() ?: 0
                waiting = metrics["vllm:num_requests_waiting"]?.toDoubleOrNull()?.toInt() ?: 0
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
            LiveSnapshot(sb.toString(), base, genTokens, running, waiting, models)
        } catch (_: Exception) {
            null
        }
    }

    // UI-thread hop after a successful live fetch: feed sparkline, meter and galaxy.
    private fun applyLiveSnapshot(s: LiveSnapshot) {
        if (s.base != liveBase) { // server restart resets counters — restart the TPS baseline
            liveBase = s.base
            prevGenTokens = null
        }
        if (s.genTokens != null) {
            val now = System.currentTimeMillis()
            val prev = prevGenTokens
            val prevAt = prevTimeMs
            if (prev != null && prevAt != null && now > prevAt) {
                sparkline.addSample((s.genTokens - prev) * 1000.0 / (now - prevAt))
            }
            prevGenTokens = s.genTokens
            prevTimeMs = now
        }
        meter.setCounts(s.running, s.waiting)
        galaxy.setStars(s.models)
    }

    // Offline mode: canned values so the visuals keep moving.
    private fun advanceDemo() {
        liveBase = null
        prevGenTokens = null
        meter.setCounts(Demo.RUNNING[demoTpsIndex % Demo.RUNNING.size], Demo.WAITING[demoTpsIndex % Demo.WAITING.size])
        sparkline.addSample(Demo.TPS[demoTpsIndex % Demo.TPS.size])
        galaxy.setStars(Demo.STARS)
        demoTpsIndex++
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
        sb.appendLine("Models: 1  (snapshot 21.10 11:30)")
        sb.appendLine("  - Qwen3.8-Flash  [ready]  ctx 262144")
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
                if (latest.isNotBlank() && isNewerVersion(latest, APP_VERSION)) {
                    val url = latestDownloadUrl(latest)
                    runOnUiThread {
                        if (isDestroyed()) return@runOnUiThread
                        if (url != null) showUpdateButton("Update to $latest", url) else hideUpdateButton()
                    }
                } else {
                    runOnUiThread { if (!isDestroyed()) hideUpdateButton() }
                }
            } catch (_: Exception) { /* no internet or no release yet */ }
        }
    }

    // Only a semantically newer tag counts as an update; "different" (e.g. local 1.1.0 vs
    // released 1.0.2) must NOT offer a downgrade.
    private fun isNewerVersion(latest: String, current: String): Boolean {
        val a = latest.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
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

    private fun showUpdateButton(label: String, apkUrl: String) {
        if (updateButton == null) {
            updateButton = Button(this)
            updateSlot.addView(updateButton, LinearLayout.LayoutParams(-2, -2))
        }
        updateButton?.text = label
        updateButton?.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                // One UI may have no settings screen for this: never let it throw.
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).setData(Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    openInBrowser("https://github.com/$REPO/releases/latest")
                }
                return@setOnClickListener
            }
            openInBrowser(apkUrl)
        }
    }

    private fun hideUpdateButton() {
        updateSlot.removeView(updateButton)
        updateButton = null
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

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}

// ---- visual model ----------------------------------------------------------

data class StarModel(val id: String, val state: String, val bytesResident: Double)

// Canned offline values so visuals move without a server (demo model count matches demoText).
object Demo {
    val RUNNING = intArrayOf(1, 3, 4, 2, 6, 3, 1, 2)
    val WAITING = intArrayOf(0, 0, 2, 1, 3, 0, 0, 1)
    val TPS = doubleArrayOf(42.0, 55.0, 71.0, 63.0, 88.0, 74.0, 96.0, 81.0, 58.0, 47.0, 66.0, 92.0)
    val STARS = listOf(
        StarModel("Qwen3.8-Flash", "ready", 75.3e9),
    )
}

// Rolling area chart of generated-tokens-per-second (last 60 samples).
class SparklineView(context: Context) : View(context) {
    private val samples = ArrayDeque<Double>()
    private val maxSamples = 60
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xFF58A6FF.toInt()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3FB950.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFDDDDDD.toInt(); textSize = 24f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22FFFFFF; strokeWidth = 1f; style = Paint.Style.STROKE
    }
    private val path = Path()

    fun addSample(v: Double) {
        samples.addLast(v)
        while (samples.size > maxSamples) samples.removeFirst()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawText("tok/s", 8f, textPaint.textSize + 6f, textPaint)
        if (samples.size < 2) return
        val vmax = maxOf(1.0, samples.max())
        path.reset()
        for ((i, v) in samples.withIndex()) {
            val x = w * i / (maxSamples - 1)
            val y = h - (h - 12f) * (v / vmax).toFloat() - 4f
            if (i == 0) path.moveTo(x.toFloat(), y) else path.lineTo(x.toFloat(), y)
        }
        // gradient fill under the curve: last point -> baseline -> first point
        val lastX = w * (samples.size - 1) / (maxSamples - 1)
        path.lineTo(lastX, h)
        path.lineTo(0f, h)
        path.close()
        fillPaint.shader = LinearGradient(0f, 0f, 0f, h, 0x6658A6FF, 0x0058A6FF, Shader.TileMode.CLAMP)
        canvas.drawPath(path, fillPaint)
        fillPaint.shader = null
        // re-stroke just the curve (skip the closing baseline segments)
        path.rewind()
        for ((i, v) in samples.withIndex()) {
            val x = w * i / (maxSamples - 1)
            val y = h - (h - 12f) * (v / vmax).toFloat() - 4f
            if (i == 0) path.moveTo(x.toFloat(), y) else path.lineTo(x.toFloat(), y)
        }
        canvas.drawLine(0f, h - 0.5f, w, h - 0.5f, gridPaint)
        canvas.drawPath(path, linePaint)
        val lastV = samples.last()
        val lx = w * (samples.size - 1) / (maxSamples - 1)
        val ly = h - (h - 12f) * (lastV / vmax).toFloat() - 4f
        canvas.drawCircle(lx, ly, 6f, dotPaint)
        canvas.drawText(String.format("%.1f", lastV), 8f, h - 8f, textPaint)
    }
}

// Horizontal bars: running / waiting request counts.
class ActivityMeterView(context: Context) : View(context) {
    private var running = 0
    private var waiting = 0
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFFFFF }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFDDDDDD.toInt(); textSize = 22f
    }

    fun setCounts(r: Int, w: Int) {
        if (r != running || w != waiting) { running = r; waiting = w; invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val scale = 8f // dp-ish per request bar; capped by width
        val bw = w / 16f
        val bh = 18f
        // running bar
        canvas.drawText("running: $running", 8f, 18f, textPaint)
        val r1 = 30f
        canvas.drawRoundRect(8f, r1, w - 8f, r1 + bh, 6f, 6f, bgPaint)
        barPaint.color = 0xFF3FB950.toInt()
        val rw = min(w - 16f, bw * running)
        if (rw > 0) canvas.drawRoundRect(8f, r1, 8f + rw, r1 + bh, 6f, 6f, barPaint)
        // waiting bar
        canvas.drawText("waiting: $waiting", 8f, r1 + bh + 22f, textPaint)
        val r2 = r1 + bh + 30f
        canvas.drawRoundRect(8f, r2, w - 8f, r2 + bh, 6f, 6f, bgPaint)
        barPaint.color = 0xFFD29922.toInt()
        val ww = min(w - 16f, bw * waiting)
        if (ww > 0) canvas.drawRoundRect(8f, r2, 8f + ww, r2 + bh, 6f, 6f, barPaint)
    }
}

// Animated particle field: every served model is a star with orbiting particles.
class GalaxyView(context: Context) : View(context) {
    private class Star(val id: String, val hue: Int, val weight: Float, val gb: Double, val radius: Float,
                       val speed: Float, val particles: Int, val seed: Float)
    private val bgPaint = Paint().apply { color = 0x11FFFFFF }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt(); textSize = 20f
    }
    private val bgStars = List(40) {
        floatArrayOf(Random.nextFloat(), Random.nextFloat(), 1f + Random.nextFloat() * 2f)
    }
    private var stars = emptyList<Star>()
    private var angle = 0f
    private var ticking = false
    private var lastFrameNs = 0L

    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!ticking) return
            val dt = if (lastFrameNs == 0L) 33_000_000L else frameTimeNanos - lastFrameNs
            lastFrameNs = frameTimeNanos
            // ~30fps cap
            angle += dt / 1_000_000_000f * 0.9f
            invalidate()
            Choreographer.getInstance().postFrameCallbackDelayed(this, 25)
        }
    }

    fun resumeTicker() {
        if (ticking) return
        ticking = true; lastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(frameCb)
    }

    fun pauseTicker() {
        ticking = false
        Choreographer.getInstance().removeFrameCallback(frameCb)
    }

    fun setStars(models: List<StarModel>) {
        val next = models.map { m ->
            val weight = min(1f, (m.bytesResident / 1e9 / 80.0).toFloat())
            val hue = when {
                m.state == "ready" -> 0xFF3FB950.toInt()
                m.state == "loading" -> 0xFFD29922.toInt()
                else -> 0xFFF0644F.toInt()
            }
            Star(m.id.substringAfterLast('-').take(18), hue, 0.35f + 0.65f * weight, m.bytesResident / 1e9,
                 10f + 26f * weight, 0.6f + 1.4f * weight, 4 + (weight * 10).toInt(), Random.nextFloat())
        }
        if (next.toString() != starsToString()) stars = next
        invalidate()
    }

    private fun starsToString() = stars.joinToString("|") { it.id }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        for (s in bgStars) canvas.drawCircle(s[0] * w, s[1] * h, s[2], bgPaint)
        if (stars.isEmpty()) return
        val cx = w / 2f; val cy = h / 2f
        val orbit = min(w, h) / 2f - 30f
        for ((i, s) in stars.withIndex()) {
            // single model -> center it; several -> spread on an ellipse
            val a = if (stars.size == 1) 0f else angle + s.seed * 6.28f + i * (6.28f / stars.size)
            val ex = if (stars.size == 1) 0f else orbit * 0.55f * cos(a)
            val ey = if (stars.size == 1) 0f else orbit * 0.35f * sin(a)
            val x = cx + ex; val y = cy + ey
            glowPaint.shader = android.graphics.RadialGradient(
                x, y, s.radius * 3f, (s.hue and 0x00FFFFFF) or (0x55 shl 24), 0, Shader.TileMode.CLAMP)
            canvas.drawCircle(x, y, s.radius * 3f, glowPaint)
            glowPaint.shader = null
            starPaint.color = s.hue
            canvas.drawCircle(x, y, s.radius, starPaint)
            // orbiting particles
            for (p in 0 until s.particles) {
                val pa = angle * s.speed + p * (6.28f / s.particles) + s.seed
                val pr = s.radius + 14f + 6f * sin(pa * 3f + s.seed)
                canvas.drawCircle(x + pr * cos(pa), y + pr * sin(pa) * 0.6f, 3f, starPaint)
            }
            val label = s.id + (if (s.gb > 0) String.format("  %.0f GB", s.gb) else "")
            canvas.drawText(label, x - textPaint.measureText(label) / 2f,
                            y + s.radius + 26f, textPaint)
        }
    }
}

