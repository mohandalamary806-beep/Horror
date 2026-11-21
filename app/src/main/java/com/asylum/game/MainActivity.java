package com.asylum.game;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/asylum_game.html";

    /** مكتبات الـ CDN اللي اللعبة بتحمّلها. لو الملفات المحلية موجودة جوه assets/libs بنقدّمها منها (أوفلاين). */
    private static final Map<String, String> LOCAL_LIBS = new HashMap<>();
    static {
        LOCAL_LIBS.put("https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js", "libs/three.min.js");
        LOCAL_LIBS.put("https://cdn.jsdelivr.net/npm/three@0.128.0/examples/js/loaders/GLTFLoader.js", "libs/GLTFLoader.js");
    }

    /** بيوقّف اللعبة مؤقتاً لو شغّالة (نفس زر "كمّل" اللي في اللعبة). */
    private static final String PAUSE_JS =
            "(function(){try{if(typeof state!=='undefined'&&state==='play'&&typeof pauseGame==='function'){pauseGame();return 'paused'}}catch(e){}return 'none'})()";

    /** زر الرجوع: يقفل لوحة الأونلاين/الإعدادات لو مفتوحة، وإلا يوقّف اللعبة. */
    private static final String BACK_JS =
            "(function(){try{"
                    + "var o=document.getElementById('ponl'),p=document.getElementById('pset'),f=document.getElementById('pfr');"
                    + "if(f&&f.style.display==='flex'){document.getElementById('fbk').click();return 'closed'}"
                    + "if(o&&o.style.display==='flex'){document.getElementById('obk').click();return 'closed'}"
                    + "if(p&&p.style.display==='flex'){document.getElementById('sbk').click();return 'closed'}"
                    + "if(typeof state!=='undefined'&&state==='play'&&typeof pauseGame==='function'){pauseGame();return 'paused'}"
                    + "}catch(e){}return 'none'})()";

    private WebView web;
    private WebViewAssetLoader assetLoader;
    private String seedJs;
    private boolean seedNeedsFallback = false;
    private long lastBack = 0;
    private boolean inBackground = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }

        assetLoader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setHapticFeedbackEnabled(false);
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                 // localStorage (إعدادات اللعبة)
        s.setMediaPlaybackRequiresUserGesture(false); // الصوت
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW); // عشان ws:// على الشبكة المحلية
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "AndroidApp");
        web.setWebViewClient(new GameClient());
        web.setWebChromeClient(new GameChrome());

        setupDefaultServer();

        setContentView(web, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        web.loadUrl(START_URL);
    }

    /** لو GAME_SERVER_URL متحدد في gradle.properties، بنحطه كعنوان سيرفر افتراضي قبل ما اللعبة تقرا إعداداتها. */
    private void setupDefaultServer() {
        String url = BuildConfig.GAME_SERVER_URL;
        if (url == null || url.trim().isEmpty()) return;
        seedJs = "(function(){try{var c={};try{c=JSON.parse(localStorage.ag)||{}}catch(e){}"
                + "if(!c.srv){c.srv=" + JSONObject.quote(url.trim()) + ";localStorage.ag=JSON.stringify(c)}}catch(e){}})();";
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, seedJs, Collections.singleton("https://" + HOST));
        } else {
            seedNeedsFallback = true;
        }
    }

    private class GameClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String local = LOCAL_LIBS.get(uri.toString());
            if (local != null) {
                try {
                    InputStream in = getAssets().open(local);
                    return new WebResourceResponse("application/javascript", "UTF-8", in);
                } catch (IOException ignored) {
                    // الملف المحلي مش موجود -> نسيب الطلب يروح للإنترنت عادي
                }
            }
            return assetLoader.shouldInterceptRequest(uri);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return !HOST.equals(request.getUrl().getHost()); // مفيش تنقّل برا اللعبة
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            if (seedNeedsFallback && seedJs != null) view.evaluateJavascript(seedJs, null);
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // الموبايل خلّصت منه الذاكرة أو العارض وقع: نعيد تشغيل الشاشة بدل ما التطبيق يقفل
            Toast.makeText(MainActivity.this, R.string.render_gone, Toast.LENGTH_LONG).show();
            runOnUiThread(MainActivity.this::recreate);
            return true;
        }
    }

    /** جسر بين اللعبة والأندرويد: مشاركة/نسخ/لصق كود دعوة الصديق. */
    private class Bridge {
        @JavascriptInterface
        public void share(final String text) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(i, getString(R.string.share_title)));
            });
        }

        @JavascriptInterface
        public void copy(final String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("invite", text));
            });
        }

        @JavascriptInterface
        public String paste() {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip()) {
                    ClipData d = cm.getPrimaryClip();
                    if (d != null && d.getItemCount() > 0) {
                        CharSequence t = d.getItemAt(0).coerceToText(MainActivity.this);
                        return t == null ? "" : t.toString();
                    }
                }
            } catch (Exception ignored) {
            }
            return "";
        }
    }

    private class GameChrome extends WebChromeClient {
        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
            final EditText input = new EditText(MainActivity.this);
            input.setText(defaultValue == null ? "" : defaultValue);
            new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm(input.getText().toString()))
                    .setNegativeButton(android.R.string.cancel, (d, w) -> result.cancel())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
            new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
                    .setNegativeButton(android.R.string.cancel, (d, w) -> result.cancel())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
            return true;
        }

        @Override
        public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
            new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
                    .setOnCancelListener(d -> result.confirm())
                    .show();
            return true;
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript(BACK_JS, value -> {
            if (value != null && (value.contains("paused") || value.contains("closed"))) return;
            long now = SystemClock.elapsedRealtime();
            if (now - lastBack < 2000) {
                finish();
            } else {
                lastBack = now;
                Toast.makeText(this, R.string.press_back_again, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onPause() {
        inBackground = true;
        // نوقّف اللعبة الأول وبعدين نجمّد الـ WebView (الصوت والمؤقتات)
        web.evaluateJavascript(PAUSE_JS, v -> {
            if (inBackground && web != null) {
                web.onPause();
                web.pauseTimers();
            }
        });
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        inBackground = false;
        web.onResume();
        web.resumeTimers();
        hideSystemUi();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            ViewGroup parent = (ViewGroup) web.getParent();
            if (parent != null) parent.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }
}
