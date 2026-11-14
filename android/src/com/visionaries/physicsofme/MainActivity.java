package com.visionaries.physicsofme;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Physics of Me: a WebView holding the interface (assets/www/index.html) and a bridge
 * that streams the phone's physics sensors to it in small batches every 40 ms.
 * Units: acc m/s² (with gravity), gyro rad/s, mag µT, light lux, pressure hPa,
 * mic: x = pitch in Hz, y = loudness in dBFS.
 */
public class MainActivity extends Activity implements SensorEventListener {

    private WebView web;
    private SensorManager sm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder buf = new StringBuilder();
    private final Object lock = new Object();
    private final Map<String, Integer> types = new HashMap<>();
    private final Map<Integer, String> names = new HashMap<>();
    private final Set<String> active = new HashSet<>();

    private volatile boolean micOn = false;
    private boolean micWanted = false;
    private volatile boolean toneOn = false;
    private volatile double toneA = 0, toneB = 0;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        types.put("acc", Sensor.TYPE_ACCELEROMETER);
        types.put("gyro", Sensor.TYPE_GYROSCOPE);
        types.put("mag", Sensor.TYPE_MAGNETIC_FIELD);
        types.put("light", Sensor.TYPE_LIGHT);
        types.put("pressure", Sensor.TYPE_PRESSURE);
        types.put("prox", Sensor.TYPE_PROXIMITY);
        for (Map.Entry<String, Integer> e : types.entrySet()) names.put(e.getValue(), e.getKey());
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);

        WebView.setWebContentsDebuggingEnabled(true); // lets us inspect the page from the laptop while testing
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Native");
        web.loadUrl("file:///android_asset/www/index.html");
        ui.post(flusher);
    }

    private final Runnable flusher = new Runnable() {
        @Override
        public void run() {
            String out = null;
            synchronized (lock) {
                if (buf.length() > 0) {
                    out = "[" + buf + "]";
                    buf.setLength(0);
                }
            }
            if (out != null) web.evaluateJavascript("window.__sensorBatch&&window.__sensorBatch(" + out + ")", null);
            ui.postDelayed(this, 40);
        }
    };

    private static String num(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "0";
        return String.valueOf((float) v);
    }

    private void push(String type, double t, double x, double y, double z) {
        synchronized (lock) {
            if (buf.length() > 200000) return; // the page is not reading: drop rather than grow forever
            if (buf.length() > 0) buf.append(',');
            buf.append("[\"").append(type).append("\",").append(String.valueOf(t)).append(',')
                    .append(num(x)).append(',').append(num(y)).append(',').append(num(z)).append(']');
        }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        String n = names.get(e.sensor.getType());
        if (n == null) return;
        float[] v = e.values;
        push(n, e.timestamp / 1e6, v[0], v.length > 1 ? v[1] : 0, v.length > 2 ? v[2] : 0);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void register(String type) {
        Integer ty = types.get(type);
        if (ty == null) return;
        Sensor s = sm.getDefaultSensor(ty);
        if (s == null) return;
        boolean fast = type.equals("acc") || type.equals("gyro");
        // 100 Hz for motion (no special permission needed), screen rate for the slow sensors.
        sm.registerListener(this, s, fast ? 10000 : SensorManager.SENSOR_DELAY_UI);
    }

    private void unregister(String type) {
        Integer ty = types.get(type);
        if (ty == null) return;
        Sensor s = sm.getDefaultSensor(ty);
        if (s != null) sm.unregisterListener(this, s);
    }

    @Override
    protected void onResume() {
        super.onResume();
        synchronized (active) { for (String t : active) register(t); }
        if (micWanted) beginMic();
    }

    @Override
    protected void onPause() {
        super.onPause();
        sm.unregisterListener(this);
        micOn = false;
        toneOn = false;
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != 7) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) beginMic();
        else push("micdenied", SystemClock.elapsedRealtimeNanos() / 1e6, 0, 0, 0);
    }

    // ---------------- microphone: loudness and pitch (FFT peak) ----------------
    private void beginMic() {
        if (micOn) return;
        micOn = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int sr = 44100, n = 4096;
                int min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                AudioRecord rec;
                try {
                    rec = new AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, Math.max(min, n * 2));
                    rec.startRecording();
                } catch (Exception ex) {
                    micOn = false;
                    push("micdenied", SystemClock.elapsedRealtimeNanos() / 1e6, 0, 0, 0);
                    return;
                }
                short[] s = new short[n];
                double[] re = new double[n], im = new double[n];
                while (micOn) {
                    int got = 0;
                    while (got < n && micOn) {
                        int r = rec.read(s, got, n - got);
                        if (r <= 0) break;
                        got += r;
                    }
                    if (got < n) continue;
                    double sum = 0;
                    for (int i = 0; i < n; i++) {
                        double v = s[i] / 32768.0;
                        sum += v * v;
                        re[i] = v * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1)));
                        im[i] = 0;
                    }
                    double db = 20 * Math.log10(Math.sqrt(sum / n) + 1e-9);
                    fft(re, im);
                    int lo = (int) (60.0 * n / sr), hi = (int) (5000.0 * n / sr);
                    int k = lo;
                    double best = -1;
                    for (int i = lo; i <= hi; i++) {
                        double m = re[i] * re[i] + im[i] * im[i];
                        if (m > best) { best = m; k = i; }
                    }
                    double a = mag(re, im, k - 1), b = mag(re, im, k), c = mag(re, im, k + 1);
                    double den = a - 2 * b + c;
                    double delta = den != 0 ? 0.5 * (a - c) / den : 0;
                    double f = (k + delta) * sr / n;
                    push("mic", SystemClock.elapsedRealtimeNanos() / 1e6, f, db, 0);
                }
                try { rec.stop(); } catch (Exception ignored) { }
                rec.release();
            }
        }).start();
    }

    private static double mag(double[] re, double[] im, int i) {
        return Math.sqrt(re[i] * re[i] + im[i] * im[i]);
    }

    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int j = 0; j < len / 2; j++) {
                    int u = i + j, v = i + j + len / 2;
                    double xr = re[v] * cr - im[v] * ci, xi = re[v] * ci + im[v] * cr;
                    re[v] = re[u] - xr; im[v] = im[u] - xi;
                    re[u] += xr; im[u] += xi;
                    double nr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = nr;
                }
            }
        }
    }

    // ---------------- speaker: one or two pure tones ----------------
    private void beginTone() {
        if (toneOn) return;
        toneOn = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int sr = 44100;
                int min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                AudioTrack tr = new AudioTrack(AudioManager.STREAM_MUSIC, sr, AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 8192), AudioTrack.MODE_STREAM);
                tr.play();
                short[] out = new short[1024];
                double pa = 0, pb = 0, twoPi = 2 * Math.PI;
                while (toneOn) {
                    double a = toneA, b = toneB, gain = (a > 0 && b > 0) ? 0.42 : 0.8;
                    for (int i = 0; i < out.length; i++) {
                        double v = 0;
                        if (a > 0) v += Math.sin(pa);
                        if (b > 0) v += Math.sin(pb);
                        pa += twoPi * a / sr; if (pa > twoPi) pa -= twoPi;
                        pb += twoPi * b / sr; if (pb > twoPi) pb -= twoPi;
                        out[i] = (short) (v * gain * 32767);
                    }
                    tr.write(out, 0, out.length);
                }
                try { tr.stop(); } catch (Exception ignored) { }
                tr.release();
            }
        }).start();
    }

    // ---------------- the bridge the page calls ----------------
    private class Bridge {
        @JavascriptInterface
        public String available() {
            JSONObject o = new JSONObject();
            try {
                for (Map.Entry<String, Integer> e : types.entrySet())
                    o.put(e.getKey(), sm.getDefaultSensor(e.getValue()) != null);
                o.put("mic", getPackageManager().hasSystemFeature(PackageManager.FEATURE_MICROPHONE));
                o.put("tone", true);
            } catch (Exception ignored) { }
            return o.toString();
        }

        @JavascriptInterface
        public void start(String type) {
            synchronized (active) { active.add(type); }
            register(type);
        }

        @JavascriptInterface
        public void stop(String type) {
            synchronized (active) { active.remove(type); }
            unregister(type);
        }

        @JavascriptInterface
        public void startMic() {
            micWanted = true;
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
                    else beginMic();
                }
            });
        }

        @JavascriptInterface
        public void stopMic() {
            micWanted = false;
            micOn = false;
        }

        @JavascriptInterface
        public void tone(double a, double b) {
            toneA = a;
            toneB = b;
            beginTone();
        }

        @JavascriptInterface
        public void toneStop() {
            toneOn = false;
        }
    }
}
