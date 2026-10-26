package com.realgen.offline;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Random;

public class MainActivity extends Activity {
    // Realistic Vision V6.0 B1 (SD 1.5 based, few-step "Hyper" variant), quantized GGUF.
    private static final String MODEL_URL =
            "https://huggingface.co/second-state/Realistic_Vision_V6.0_B1-GGUF/resolve/main/"
                    + "realisticVisionV60B1_v51HyperVAE-Q4_0.gguf";
    private static final String LCM_LORA_URL =
            "https://huggingface.co/latent-consistency/lcm-lora-sdv1-5/resolve/main/"
                    + "pytorch_lora_weights.safetensors";

    private static final String STYLE = ", RAW photo, ultra-realistic, natural skin texture, "
            + "detailed pores, soft natural lighting, 85mm lens, shot on Canon EOS R5, "
            + "film grain, highly detailed";
    private static final String DEFAULT_NEG = "cartoon, anime, illustration, painting, 3d render, "
            + "plastic skin, airbrushed, deformed, extra fingers, bad hands, bad anatomy, "
            + "disfigured, blurry, lowres, watermark, text, oversaturated";

    private static final int REQ_MODEL = 1;
    private static final int REQ_LORA = 2;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView status;
    private ProgressBar bar;
    private EditText prompt, negative, steps, cfg, seed, threads, loraStrength;
    private Spinner size;
    private Button btnDownload, btnImportModel, btnLora, btnLcm, btnGen, btnSave;
    private TextView loraLabel;
    private ImageView image;

    private volatile boolean busy = false;
    private volatile boolean generating = false;
    private boolean loaded = false;
    private String loadedPath = null;
    private String loraPath = "";
    private Bitmap bitmap;
    private boolean nativeOk = false;
    private String nativeError = "";
    private TextView logView;
    private android.content.SharedPreferences prefs;

    private final String[] sizes = {"512x512", "384x384", "512x768", "768x512", "448x640"};

    // ---------------------------------------------------------------- UI helpers

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView label(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(0xFF9AA4B2);
        v.setTextSize(12);
        v.setPadding(0, dp(12), 0, dp(4));
        return v;
    }

    private EditText edit(String hint, String value, int lines) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextColor(0xFFFFFFFF);
        e.setHintTextColor(0xFF6C7686);
        e.setBackgroundColor(0xFF1A1D24);
        e.setPadding(dp(10), dp(10), dp(10), dp(10));
        if (lines > 1) {
            e.setMinLines(lines);
            e.setGravity(Gravity.TOP);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        }
        return e;
    }

    private Button button(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : views) {
            r.addView(v, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        return r;
    }

    private LinearLayout labeled(String l, View v) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(0, 0, dp(6), 0);
        c.addView(label(l));
        c.addView(v);
        return c;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(32));
        root.setBackgroundColor(0xFF0F1115);

        TextView title = new TextView(this);
        title.setText("RealGen Offline");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(20);
        root.addView(title);

        status = new TextView(this);
        status.setTextColor(0xFF9AB0C8);
        status.setTextSize(13);
        status.setPadding(0, dp(6), 0, dp(6));
        root.addView(status);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        root.addView(bar);

        btnDownload = button("Download model (about 1.6 GB, one time)", v -> downloadModel());
        btnImportModel = button("Or import model file (.gguf / .safetensors)", v -> pick(REQ_MODEL));
        root.addView(btnDownload);
        root.addView(btnImportModel);

        loraLabel = label("LoRA: none");
        root.addView(loraLabel);
        btnLora = button("Import LoRA (.safetensors)", v -> pick(REQ_LORA));
        btnLcm = button("Download speed LoRA (LCM, 135 MB)", v -> downloadLcm());
        root.addView(row(btnLora, btnLcm));
        root.addView(button("Use no LoRA", v -> {
            loraPath = "";
            refresh();
            setStatus("LoRA cleared. Generating without LoRA.");
        }));
        loraStrength = edit("1.0", "1.0", 1);
        loraStrength.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        root.addView(labeled("LoRA strength", loraStrength));

        root.addView(label("Prompt"));
        prompt = edit("e.g. 28 year old woman, freckles, denim jacket, golden hour street", "", 3);
        root.addView(prompt);
        root.addView(label("Negative prompt (empty = good default)"));
        negative = edit("", "", 2);
        root.addView(negative);

        size = new Spinner(this);
        size.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, sizes));
        steps = edit("steps", "8", 1);
        steps.setInputType(InputType.TYPE_CLASS_NUMBER);
        cfg = edit("cfg", "2.5", 1);
        cfg.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        root.addView(row(labeled("Size", size), labeled("Steps", steps), labeled("CFG", cfg)));

        seed = edit("random", "", 1);
        seed.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        int cores = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
        threads = edit("threads", String.valueOf(cores), 1);
        threads.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(row(labeled("Seed", seed), labeled("CPU threads", threads)));

        btnGen = button("Generate", v -> onGenerate());
        root.addView(btnGen);

        image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setPadding(0, dp(12), 0, 0);
        root.addView(image, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        btnSave = button("Save to Pictures/RealGenOffline", v -> saveImage());
        btnSave.setVisibility(View.GONE);
        root.addView(btnSave);

        logView = new TextView(this);
        logView.setTextColor(0xFFB0B8C4);
        logView.setTextSize(11);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setPadding(0, dp(16), 0, 0);
        root.addView(logView);
        root.addView(button("Copy log", v -> copyLog()));

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        // Report what happened last time (a native crash closes the app silently).
        prefs = getSharedPreferences("state", MODE_PRIVATE);
        String prevStage = prefs.getString("stage", "idle");
        String prevLog = readLog();
        try {
            Native.sysInfo();
            nativeOk = true;
        } catch (Throwable t) {
            nativeError = String.valueOf(t);
        }
        StringBuilder rep = new StringBuilder();
        if (!nativeOk) rep.append("Native engine failed to load: ").append(nativeError).append("\n");
        if (!"idle".equals(prevStage)) {
            rep.append("Last run closed unexpectedly during: ").append(prevStage).append("\n");
        }
        if (!prevLog.isEmpty()) rep.append("--- engine log (end) ---\n").append(prevLog);
        logView.setText(rep.toString());
        prefs.edit().putString("stage", "idle").commit();

        refresh();
    }

    private File logFile() {
        return new File(getExternalFilesDir(null), "engine.log");
    }

    private String readLog() {
        try {
            File f = logFile();
            if (!f.exists()) return "";
            long len = f.length();
            int max = 3000;
            try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
                long start = Math.max(0, len - max);
                r.seek(start);
                byte[] b = new byte[(int) (len - start)];
                r.readFully(b);
                return new String(b, "UTF-8");
            }
        } catch (Exception e) {
            return "";
        }
    }

    private void copyLog() {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("log", logView.getText()));
        setStatus("Log copied.");
    }

    private void setStage(String s) {
        if (prefs != null) prefs.edit().putString("stage", s).commit();
    }

    // ---------------------------------------------------------------- state

    private File modelsDir() {
        File d = new File(getExternalFilesDir(null), "models");
        d.mkdirs();
        return d;
    }

    private File findModel() {
        File[] fs = modelsDir().listFiles();
        if (fs == null) return null;
        for (File f : fs) {
            if (f.getName().startsWith("model.") && !f.getName().endsWith(".part")) return f;
        }
        return null;
    }

    private void post(Runnable r) {
        ui.post(r);
    }

    private void setStatus(String s) {
        post(() -> status.setText(s));
    }

    private void refresh() {
        File m = findModel();
        status.setText(m != null
                ? "Model ready (" + (m.length() / 1048576) + " MB). Works fully offline."
                : "No model yet. Download it once (Wi-Fi recommended) or import a file.");
        btnDownload.setVisibility(m != null ? View.GONE : View.VISIBLE);
        btnImportModel.setVisibility(m != null ? View.GONE : View.VISIBLE);
        loraLabel.setText(loraPath.isEmpty() ? "LoRA: none"
                : "LoRA: " + new File(loraPath).getName());
    }

    private void setBusy(boolean b) {
        busy = b;
        post(() -> {
            btnDownload.setEnabled(!b);
            btnImportModel.setEnabled(!b);
            btnLora.setEnabled(!b);
            btnLcm.setEnabled(!b);
            if (b) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                bar.setProgress(0);
                btnGen.setText("Generate");
                refresh();
            }
        });
    }

    // ---------------------------------------------------------------- download / import

    private void downloadModel() {
        if (busy) return;
        final File target = new File(modelsDir(), "model.gguf");
        runDownload(MODEL_URL, target, 200L * 1048576L, "model");
    }

    private void downloadLcm() {
        if (busy) return;
        final File target = new File(modelsDir(), "lcm-lora-sd15.safetensors");
        runDownload(LCM_LORA_URL, target, 50L * 1048576L, "LCM LoRA");
    }

    private void runDownload(final String url, final File target, final long minBytes, final String what) {
        setBusy(true);
        new Thread(() -> {
            File part = new File(target.getPath() + ".part");
            try {
                long have = part.exists() ? part.length() : 0;
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(20000);
                c.setReadTimeout(30000);
                if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
                c.connect();
                int code = c.getResponseCode();
                if (code == 416 && have >= minBytes) {
                    // Already fully downloaded earlier.
                } else {
                    if (code != 200 && code != 206) throw new IOException("HTTP " + code);
                    boolean resume = code == 206;
                    if (!resume) have = 0;
                    long total = c.getContentLengthLong() + have;
                    long done = have;
                    long lastUi = 0;
                    try (InputStream in = c.getInputStream();
                         FileOutputStream out = new FileOutputStream(part, resume)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            done += n;
                            long now = SystemClock.elapsedRealtime();
                            if (now - lastUi > 400) {
                                lastUi = now;
                                final int pct = total > 0 ? (int) (done * 100 / total) : 0;
                                final long mb = done / 1048576;
                                post(() -> {
                                    bar.setProgress(pct);
                                    status.setText("Downloading " + what + ": " + mb + " MB (" + pct + "%)");
                                });
                            }
                        }
                    }
                }
                if (part.length() < minBytes) throw new IOException("Download incomplete, tap again to resume");
                if (target.exists()) target.delete();
                if (!part.renameTo(target)) throw new IOException("Could not save file");
                if (what.startsWith("LCM")) loraPath = target.getPath();
                setStatus("Downloaded " + what + ".");
            } catch (Exception e) {
                setStatus("Download failed: " + e.getMessage() + " (tap again to resume)");
            } finally {
                setBusy(false);
            }
        }).start();
    }

    private void pick(int req) {
        if (busy) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, req);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final boolean isModel = req == REQ_MODEL;
        setBusy(true);
        new Thread(() -> {
            try {
                String name = displayName(uri);
                String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : ".gguf";
                File dest;
                if (isModel) {
                    File old = findModel();
                    if (old != null) old.delete();
                    dest = new File(modelsDir(), "model" + ext);
                } else {
                    dest = new File(modelsDir(), name);
                }
                setStatus("Copying " + name + "...");
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                if (isModel) {
                    loaded = false;
                } else {
                    loraPath = dest.getPath();
                }
                setStatus("Imported " + name);
            } catch (Exception e) {
                setStatus("Import failed: " + e.getMessage());
            } finally {
                setBusy(false);
            }
        }).start();
    }

    /** Returns null if the LoRA looks usable with SD 1.5, otherwise a message. */
    private String checkLora(File f) {
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
            byte[] lb = new byte[8];
            r.readFully(lb);
            long n = 0;
            for (int i = 7; i >= 0; i--) n = (n << 8) | (lb[i] & 0xFF);
            if (n <= 0 || n > 100000000L) return "This does not look like a valid .safetensors LoRA file.";
            byte[] hb = new byte[(int) n];
            r.readFully(hb);
            org.json.JSONObject h = new org.json.JSONObject(new String(hb, "UTF-8"));
            java.util.Iterator<String> it = h.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (k.contains("attn2") && k.contains("to_k")
                        && (k.contains("lora_down") || k.contains("lora_A"))) {
                    org.json.JSONArray sh = h.getJSONObject(k).getJSONArray("shape");
                    if (sh.length() != 2) continue;
                    int in = sh.getInt(1);
                    if (in == 768) return null;
                    if (in == 2048) {
                        return "This LoRA is for SDXL and cannot be used with the SD 1.5 model. "
                                + "Use an SD 1.5 LoRA (or tap 'Use no LoRA').";
                    }
                    return "This LoRA is not for SD 1.5 (input size " + in + "). Use an SD 1.5 LoRA.";
                }
            }
            return null; // could not tell; allow
        } catch (Exception e) {
            return null;
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {
        }
        return "file";
    }

    // ---------------------------------------------------------------- generation

    private void onGenerate() {
        if (generating) {
            Native.cancel();
            setStatus("Cancelling...");
            return;
        }
        if (busy) return;
        if (!nativeOk) {
            setStatus("Native engine failed to load: " + nativeError);
            return;
        }
        final File model = findModel();
        if (model == null) {
            setStatus("Download or import a model first.");
            return;
        }

        final String userPrompt = prompt.getText().toString().trim();
        final String fullPrompt = (userPrompt.isEmpty()
                ? "portrait photo of a young woman, natural light" : userPrompt) + STYLE;
        String n = negative.getText().toString().trim();
        final String neg = n.isEmpty() ? DEFAULT_NEG : n;
        final int nSteps = clampInt(steps.getText().toString(), 8, 1, 50);
        final float nCfg = clampFloat(cfg.getText().toString(), 2.5f, 1f, 15f);
        final int nThreads = clampInt(threads.getText().toString(), 4, 1, 8);
        final float lora = clampFloat(loraStrength.getText().toString(), 1f, 0f, 2f);
        String[] wh = sizes[size.getSelectedItemPosition()].split("x");
        final int w = Integer.parseInt(wh[0]);
        final int h = Integer.parseInt(wh[1]);
        long sd;
        try {
            sd = Long.parseLong(seed.getText().toString().trim());
            if (sd < 0) sd = new Random().nextInt(Integer.MAX_VALUE);
        } catch (Exception e) {
            sd = new Random().nextInt(Integer.MAX_VALUE);
        }
        final long finalSeed = sd;
        final String loraFile = loraPath;
        if (!loraFile.isEmpty()) {
            String bad = checkLora(new File(loraFile));
            if (bad != null) {
                setStatus(bad);
                return;
            }
        }

        generating = true;
        setBusy(true);
        post(() -> {
            btnGen.setText("Cancel");
            btnGen.setEnabled(true);
        });

        // Large stack: the native engine can overflow Android's default 1 MB thread stack.
        new Thread(null, () -> {
            try {
                if (!loaded || !model.getPath().equals(loadedPath)) {
                    setStatus("Loading model (first time takes a while)...");
                    setStage("loading model");
                    Native.setLogFile(logFile().getPath());
                    loaded = Native.load(model.getPath(), nThreads);
                    loadedPath = model.getPath();
                    if (!loaded) {
                        throw new RuntimeException("Model failed to load. " + Native.lastLog());
                    }
                }
                final long t0 = SystemClock.elapsedRealtime();
                Native.progress = (step, total) -> post(() -> {
                    bar.setMax(Math.max(1, total));
                    bar.setProgress(step);
                    status.setText("Step " + step + "/" + total + "  ("
                            + (SystemClock.elapsedRealtime() - t0) / 1000 + "s)");
                });
                setStatus("Generating...");
                setStage("generating image");
                int[] r = Native.generate(fullPrompt, neg, loraFile, lora, w, h, nSteps, nCfg, finalSeed);
                Native.progress = null;
                if (r == null || r.length < 3) {
                    String log = Native.lastLog();
                    setStatus("Stopped or failed." + (log.isEmpty() ? "" : " " + log));
                    return;
                }
                final int iw = r[0];
                final int ih = r[1];
                final Bitmap bm = Bitmap.createBitmap(r, 2, iw, iw, ih, Bitmap.Config.ARGB_8888);
                final long secs = (SystemClock.elapsedRealtime() - t0) / 1000;
                post(() -> {
                    bitmap = bm;
                    image.setImageBitmap(bm);
                    btnSave.setVisibility(View.VISIBLE);
                    status.setText("Done in " + secs + "s (seed " + finalSeed + ")");
                });
            } catch (Throwable t) {
                setStatus("Error: " + t.getMessage());
            } finally {
                Native.progress = null;
                generating = false;
                setStage("idle");
                setBusy(false);
            }
        }, "sd-worker", 128L * 1024 * 1024).start();
    }

    private static int clampInt(String s, int def, int lo, int hi) {
        try {
            return Math.max(lo, Math.min(hi, Integer.parseInt(s.trim())));
        } catch (Exception e) {
            return def;
        }
    }

    private static float clampFloat(String s, float def, float lo, float hi) {
        try {
            return Math.max(lo, Math.min(hi, Float.parseFloat(s.trim())));
        } catch (Exception e) {
            return def;
        }
    }

    // ---------------------------------------------------------------- save

    private void saveImage() {
        if (bitmap == null) return;
        try {
            ContentResolver cr = getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, "realgen_" + System.currentTimeMillis() + ".png");
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RealGenOffline");
            Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("MediaStore insert failed");
            try (OutputStream out = cr.openOutputStream(uri)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            setStatus("Saved to Pictures/RealGenOffline");
        } catch (Exception e) {
            setStatus("Save failed: " + e.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (nativeOk && isFinishing() && !generating) Native.unload();
    }
}
