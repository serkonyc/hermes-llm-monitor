package ai.serge.llmmonitor;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hermes LLM Monitor — pure Java (no Kotlin, no stdlib): this device kills Kotlin
 * activities before onCreate (proven by v1.2.x diagnostics), so the app avoids Kotlin entirely.
 * Demo-first: works offline from canned data; live mlx-serve metrics overlay when reachable.
 */
public class MonitorActivity extends Activity {

    private static final String ENDPOINT_LOOPBACK = "http://127.0.0.1:18422";
    private static final String ENDPOINT_LAN = "http://192.168.1.96:18422";
    private static final long REFRESH_SECONDS = 10L;
    private static final int MIN_INNER_WIDTH_DP = 560;

    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextView titleView, statusView;
    private Button refreshButton;
    private SparklineView sparkline;
    private ActivityMeterView meter;
    private GalaxyView galaxy;
    private LinearLayout visualColumn;

    private String lastLiveText = null;
    private String liveBase = null;
    private Double prevGenTokens = null;
    private Long prevTimeMs = null;
    private int demoIndex = 0;

    private final Runnable refreshTick = new Runnable() {
        @Override public void run() {
            doRefresh();
            mainHandler.postDelayed(this, REFRESH_SECONDS * 1000L);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        titleView = new TextView(this);
        titleView.setTextSize(22f);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setText("Hermes LLM Monitor");
        titleView.setGravity(Gravity.CENTER_HORIZONTAL);

        statusView = new TextView(this);
        statusView.setTextSize(13f);
        statusView.setTextColor(0xFFB0C4DE);
        statusView.setPadding(24, 24, 24, 24);

        refreshButton = new Button(this);
        refreshButton.setText("Refresh");
        refreshButton.setOnClickListener(v -> doRefresh());

        galaxy = new GalaxyView(this);
        sparkline = new SparklineView(this);
        meter = new ActivityMeterView(this);

        visualColumn = new LinearLayout(this);
        visualColumn.setOrientation(LinearLayout.VERTICAL);
        visualColumn.setPadding(24, 24, 24, 24);
        visualColumn.addView(galaxy, new LinearLayout.LayoutParams(-1, dp(220)));
        visualColumn.addView(sparkline, new LinearLayout.LayoutParams(-1, dp(120)));
        visualColumn.addView(meter, new LinearLayout.LayoutParams(-1, dp(64)));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(statusView);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0D1117);
        root.addView(titleView);
        root.addView(refreshButton, new LinearLayout.LayoutParams(-2, -2, 0f));
        root.addView(visualColumn, new LinearLayout.LayoutParams(-1, 0, 1.2f));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        galaxy.resumeTicker();
        doRefresh();
        mainHandler.removeCallbacks(refreshTick);
        mainHandler.postDelayed(refreshTick, REFRESH_SECONDS * 1000L);
    }

    @Override protected void onPause() {
        mainHandler.removeCallbacks(refreshTick);
        galaxy.pauseTicker();
        super.onPause();
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void doRefresh() {
        refreshButton.setEnabled(false);
        pool.execute(() -> {
            LiveSnapshot snap = fetchLive();
            String text = snap != null ? snap.text : demoText(lastLiveText);
            if (snap != null) lastLiveText = snap.text;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                statusView.setText(text);
                refreshButton.setEnabled(true);
                if (snap != null) applyLive(snap); else advanceDemo();
            });
        });
    }

    private static final class LiveSnapshot {
        final String text, base; final Double genTokens; final int running, waiting;
        final List<StarModel> models;
        LiveSnapshot(String text, String base, Double genTokens, int running, int waiting, List<StarModel> models) {
            this.text = text; this.base = base; this.genTokens = genTokens;
            this.running = running; this.waiting = waiting; this.models = models;
        }
    }

    private LiveSnapshot fetchLive() {
        long t0 = System.currentTimeMillis();
        String body = null, base = ENDPOINT_LOOPBACK;
        for (String ep : new String[]{ENDPOINT_LOOPBACK, ENDPOINT_LAN}) {
            try { body = httpGet(ep + "/v1/models"); base = ep; break; } catch (Exception ignored) {}
        }
        if (body == null) return null;
        try {
            long latency = System.currentTimeMillis() - t0;
            StringBuilder sb = new StringBuilder();
            sb.append("Status: ONLINE  (").append(latency).append(" ms)\n");
            sb.append("Endpoint: ").append(base).append("\n\n");
            JSONArray arr = new JSONObject(body).optJSONArray("data");
            if (arr == null) arr = new JSONArray();
            List<StarModel> models = new ArrayList<>();
            sb.append("Models: ").append(arr.length()).append("\n");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.getJSONObject(i);
                String id = m.optString("id");
                String state = m.optString("state", "ready");
                models.add(new StarModel(id, state, m.optDouble("bytes_resident", 0.0)));
                sb.append("  - ").append(id).append("  [").append(state)
                  .append("]  ctx ").append(m.optInt("context_length")).append("\n");
            }
            sb.append("\n");
            try {
                Map<String, String> metrics = parseMetrics(httpGet(base + "/metrics"));
                Double genTokens = dbl(metrics.get("vllm:generation_tokens_total"));
                int running = intr(metrics.get("vllm:num_requests_running"));
                int waiting = intr(metrics.get("vllm:num_requests_waiting"));
                sb.append("Live metrics:\n");
                if (metrics.containsKey("vllm:request_success_total")) sb.append("  requests: ").append(num(metrics.get("vllm:request_success_total"))).append("\n");
                if (metrics.containsKey("vllm:generation_tokens_total")) sb.append("  generated tokens: ").append(fmt(dbl(metrics.get("vllm:generation_tokens_total")).longValue())).append("\n");
                if (metrics.containsKey("vllm:num_requests_running")) sb.append("  running now: ").append(num(metrics.get("vllm:num_requests_running"))).append("\n");
                if (metrics.containsKey("mlx_serve:memory_mb")) sb.append("  server memory: ").append(num(metrics.get("mlx_serve:memory_mb"))).append(" MB\n");
                if (metrics.containsKey("mlx_serve:gpu_utilization_pct")) sb.append("  GPU: ").append(num(metrics.get("mlx_serve:gpu_utilization_pct"))).append("%\n");
                return new LiveSnapshot(sb.toString(), base, genTokens, running, waiting, models);
            } catch (Exception e) {
                sb.append("(metrics endpoint unavailable)\n");
                return new LiveSnapshot(sb.toString(), base, null, 0, 0, models);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void applyLive(LiveSnapshot s) {
        if (!s.base.equals(liveBase)) { liveBase = s.base; prevGenTokens = null; }
        if (s.genTokens != null) {
            long now = System.currentTimeMillis();
            if (prevGenTokens != null && prevTimeMs != null && now > prevTimeMs) {
                sparkline.addSample((s.genTokens - prevGenTokens) * 1000.0 / (now - prevTimeMs));
            }
            prevGenTokens = s.genTokens;
            prevTimeMs = now;
        }
        meter.setCounts(s.running, s.waiting);
        galaxy.setStars(s.models);
    }

    private void advanceDemo() {
        liveBase = null; prevGenTokens = null;
        meter.setCounts(Demo.RUNNING[demoIndex % Demo.RUNNING.length], Demo.WAITING[demoIndex % Demo.WAITING.length]);
        sparkline.addSample(Demo.TPS[demoIndex % Demo.TPS.length]);
        galaxy.setStars(Demo.STARS);
        demoIndex++;
    }

    private String demoText(String lastLive) {
        StringBuilder sb = new StringBuilder();
        sb.append("OFFLINE DEMO (tap Refresh to retry live)\n");
        if (lastLive != null) {
            sb.append("last live snapshot:\n");
            String[] lines = lastLive.trim().split("\n");
            for (int i = 0; i < Math.min(4, lines.length); i++) sb.append("  ").append(lines[i]).append("\n");
            sb.append("\n");
        }
        sb.append("Status: OFFLINE\n");
        sb.append("Tried: ").append(ENDPOINT_LOOPBACK).append(" (tunnel), ").append(ENDPOINT_LAN).append(" (LAN)\n\n");
        sb.append("Models: 1  (snapshot 21.10 11:30)\n");
        sb.append("  - Qwen3.8-Flash  [ready]  ctx 262144\n\n");
        sb.append("Live metrics (snapshot):\n");
        sb.append("  requests: 499\n");
        sb.append("  generated tokens: 122 643\n");
        sb.append("  prompt tokens: 34 212 497\n");
        sb.append("  prefix cache hit rate: 58.8%\n");
        sb.append("  running now: 1   waiting: 0\n");
        sb.append("  server memory: 75 644 MB\n");
        sb.append("  GPU: 88%\n");
        sb.append("  uptime: 4h 52m\n\n");
        sb.append("Last refresh: ").append(DateFormat.getTimeInstance().format(System.currentTimeMillis()))
          .append(" (auto ").append(REFRESH_SECONDS).append("s)\n");
        return sb.toString();
    }

    private static Map<String, String> parseMetrics(String body) {
        Map<String, String> out = new HashMap<>();
        for (String line : body.split("\n")) {
            if (line.startsWith("#") || !line.contains(" ")) continue;
            String key = line.substring(0, line.indexOf(' '));
            if (!out.containsKey(key)) out.put(key, line.substring(line.indexOf(' ') + 1).trim());
        }
        return out;
    }

    private static Double dbl(String s) { try { return s == null ? null : Double.parseDouble(s); } catch (Exception e) { return null; } }
    private static int intr(String s) { Double d = dbl(s); return d == null ? 0 : d.intValue(); }
    private static String num(String s) { Double d = dbl(s); return d == null ? s : fmt(d.longValue()); }
    private static String fmt(long n) { return String.format(Locale.US, "%,d", n).replace(',', ' '); }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(1200);
        conn.setReadTimeout(1500);
        try {
            if (conn.getResponseCode() != 200) throw new Exception("http " + conn.getResponseCode());
            BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            return sb.toString();
        } finally { conn.disconnect(); }
    }

    // ---------- visuals (all plain Canvas) ----------

    static final class StarModel {
        final String id, state; final double bytesResident;
        StarModel(String id, String state, double bytesResident) {
            this.id = id; this.state = state; this.bytesResident = bytesResident;
        }
    }

    static final class Demo {
        static final double[] TPS = {21.4, 24.8, 23.1, 31.6, 28.9, 26.4, 33.2, 29.7, 27.5, 35.1};
        static final int[] RUNNING = {1, 2, 1, 3, 2, 1, 2, 1, 3, 1};
        static final int[] WAITING = {0, 1, 0, 2, 0, 0, 1, 0, 1, 0};
        static final List<StarModel> STARS = new ArrayList<>();
        static { STARS.add(new StarModel("Qwen3.8-Flash", "ready", 75_644_000_000d)); }
    }

    static final class SparklineView extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<Double> samples = new ArrayList<>();
        SparklineView(Context c) {
            super(c);
            line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(3f); line.setColor(0xFF58A6FF);
            fill.setStyle(Paint.Style.FILL); fill.setColor(0x3358A6FF);
        }
        void addSample(double v) {
            samples.add(v);
            while (samples.size() > 60) samples.remove(0);
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            if (samples.size() < 2) return;
            double max = 0; for (double s : samples) max = Math.max(max, s);
            if (max <= 0) max = 1;
            float w = getWidth(), h = getHeight();
            Path p = new Path(), f = new Path();
            for (int i = 0; i < samples.size(); i++) {
                float x = w * i / (samples.size() - 1);
                float y = h - (float) (h * 0.9 * samples.get(i) / max) - 2;
                if (i == 0) { p.moveTo(x, y); f.moveTo(x, h); f.lineTo(x, y); }
                else { p.lineTo(x, y); f.lineTo(x, y); }
            }
            f.lineTo(w, h); f.close();
            canvas.drawPath(f, fill);
            canvas.drawPath(p, line);
        }
    }

    static final class ActivityMeterView extends View {
        private final Paint run = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint wait = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int running, waiting;
        ActivityMeterView(Context c) {
            super(c);
            run.setColor(0xFF3FB950); wait.setColor(0xFFD29922);
            txt.setColor(0xFFFFFFFF); txt.setTextSize(28f);
        }
        void setCounts(int running, int waiting) { this.running = running; this.waiting = waiting; invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            float h = getHeight(), w = getWidth();
            int total = Math.max(1, running + waiting);
            float rw = w * running / total;
            canvas.drawRoundRect(0, h * 0.35f, rw, h * 0.75f, 8, 8, run);
            canvas.drawRoundRect(rw, h * 0.35f, w, h * 0.75f, 8, 8, wait);
            canvas.drawText("running " + running + "   waiting " + waiting, 8, h * 0.28f, txt);
        }
    }

    static final class GalaxyView extends View {
        private static final class Star {
            final String id; final int hue; final float weight; final double gb;
            final float radius; float angle, orbit;
            Star(String id, int hue, float weight, double gb, float radius) {
                this.id = id; this.hue = hue; this.weight = weight; this.gb = gb; this.radius = radius;
            }
        }
        private final Paint orbit = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<Star> stars = new ArrayList<>();
        private float galAngle = 0f;
        private boolean ticking = false;
        private long lastFrameNs = 0L;
        private final Random rnd = new Random(42);
        private final Choreographer.FrameCallback frameCb = new Choreographer.FrameCallback() {
            @Override public void doFrame(long frameTimeNanos) {
                if (!ticking) return;
                long dt = lastFrameNs == 0 ? 33_000_000L : frameTimeNanos - lastFrameNs;
                lastFrameNs = frameTimeNanos;
                galAngle += dt / 1_000_000_000f * 0.9f;
                invalidate();
                Choreographer.getInstance().postFrameCallbackDelayed(this, 25);
            }
        };
        GalaxyView(Context c) {
            super(c);
            orbit.setColor(0x33888888); orbit.setStyle(Paint.Style.STROKE); orbit.setStrokeWidth(1.5f);
            body.setStyle(Paint.Style.FILL);
            txt.setColor(0xFFCCCCCC); txt.setTextSize(22f);
            setStars(Demo.STARS);
        }
        void resumeTicker() {
            if (ticking) return;
            ticking = true; lastFrameNs = 0;
            Choreographer.getInstance().postFrameCallback(frameCb);
        }
        void pauseTicker() {
            ticking = false;
            Choreographer.getInstance().removeFrameCallback(frameCb);
        }
        void setStars(List<StarModel> models) {
            List<Star> next = new ArrayList<>();
            int i = 0;
            for (StarModel m : models) {
                float weight = (float) Math.min(1.0, m.bytesResident / 1e9 / 80.0);
                int hue = m.state.equals("ready") ? 0xFF58A6FF : 0xFFD29922;
                Star s = new Star(m.id, hue, weight, m.bytesResident / 1e9,
                        10f + 26f * weight);
                s.orbit = 40f + i * 55f + rnd.nextFloat() * 10f;
                s.angle = rnd.nextFloat() * (float) (Math.PI * 2);
                next.add(s); i++;
            }
            stars.clear(); stars.addAll(next);
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            body.setShader(new android.graphics.RadialGradient(cx, cy, 60f,
                    new int[]{0x6658A6FF, 0x00000000}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, 60f, body);
            body.setShader(null);
            for (Star s : stars) {
                float a = s.angle + galAngle / (1f + s.orbit / 90f);
                float x = cx + (float) Math.cos(a) * s.orbit * 1.6f;
                float y = cy + (float) Math.sin(a) * s.orbit * 0.6f;
                canvas.drawCircle(cx, cy, s.orbit * 1.6f, orbit);
                body.setColor(s.hue);
                canvas.drawCircle(x, y, s.radius, body);
                canvas.drawText(s.id + "  " + String.format(Locale.US, "%.0f GB", s.gb),
                        Math.min(x + s.radius + 6, getWidth() - 200), y, txt);
            }
        }
    }
}
