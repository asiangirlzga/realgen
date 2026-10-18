package com.realgen.app;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    private static final int PORT = 8765;
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }
        Python.getInstance().getModule("server").callAttr("start", PORT);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("http://127.0.0.1:" + PORT + "/");
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    private class Bridge {
        /** Saves an image served by the local Python server into Pictures/RealGen. */
        @JavascriptInterface
        public void save(final String path) {
            new Thread(() -> {
                try {
                    HttpURLConnection c = (HttpURLConnection)
                            new URL("http://127.0.0.1:" + PORT + path).openConnection();
                    String type = c.getContentType();
                    if (type == null) type = "image/webp";
                    String ext = type.contains("png") ? "png" : type.contains("jpeg") ? "jpg" : "webp";

                    ContentResolver cr = getContentResolver();
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Images.Media.DISPLAY_NAME,
                            "realgen_" + System.currentTimeMillis() + "." + ext);
                    v.put(MediaStore.Images.Media.MIME_TYPE, type);
                    v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RealGen");
                    Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) throw new Exception("MediaStore insert failed");

                    try (InputStream in = c.getInputStream();
                         OutputStream out = cr.openOutputStream(uri)) {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    toast("Saved to Pictures/RealGen");
                } catch (Exception e) {
                    toast("Save failed: " + e.getMessage());
                }
            }).start();
        }
    }
}
