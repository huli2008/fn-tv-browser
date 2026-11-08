package com.fntv.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 飞牛电视浏览器 —— 极简版。
 * 一个全屏 WebView + 一个地址；首次填地址，之后开机直达。
 * 鼠标/键盘原生可用；电视遥控器走内置指针模式。
 */
public class MainActivity extends Activity {

    private static final String PREF = "fn_browser";
    private static final String KEY_URL = "url";
    private static final int BG = 0xFF0B0E13;
    private static final int TEXT = 0xFFF2F5F9;
    private static final int TEXT2 = 0xFF9AA7B8;
    private static final int TEXT3 = 0xFF6B7789;
    private static final int BLUE = 0xFF2F7BFF;

    private FrameLayout root;
    private WebView web;
    private LinearLayout toolbar;
    private SharedPreferences sp;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean setupMode = true;
    private boolean pointerMode = true;
    private boolean cursorShown = false;
    private boolean toolbarVisible = false;
    private boolean okLongFired = false;
    private long lastMouseAt = 0L;

    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private WebChromeClient chrome;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        goFullscreen();
        sp = getSharedPreferences(PREF, MODE_PRIVATE);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        handler.postDelayed(new Runnable() {
            public void run() {
                tickCursor();
                handler.postDelayed(this, 1000);
            }
        }, 1000);

        String forced = null;
        try {
            if (getIntent() != null) forced = getIntent().getStringExtra("url");
        } catch (Throwable ignored) {
        }
        if (forced == null || forced.length() == 0) {
            // 调试/批量部署用：adb push 一行地址到 files/url.txt 即可免键盘配置
            try {
                java.io.File f = new java.io.File(getExternalFilesDir(null), "url.txt");
                if (f.exists()) {
                    java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
                    String line = r.readLine();
                    r.close();
                    if (line != null && line.trim().length() > 0) forced = line.trim();
                }
            } catch (Throwable ignored) {
            }
        }
        if (forced != null && forced.length() > 0) {
            sp.edit().putString(KEY_URL, normalize(forced)).apply();
        }

        String url = sp.getString(KEY_URL, "");
        if (url == null || url.length() == 0) showSetup();
        else openBrowser(url);
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String u = intent == null ? null : intent.getStringExtra("url");
        if (u != null && u.length() > 0) {
            String n = normalize(u);
            sp.edit().putString(KEY_URL, n).apply();
            openBrowser(n);
        }
    }

    // ------------------------------------------------------------------ fullscreen

    private void goFullscreen() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has) goFullscreen();
    }

    // ------------------------------------------------------------------ setup screen

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable box(int color, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(16));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private void showSetup() {
        setupMode = true;
        if (web != null) {
            root.removeView(web);
            web.destroy();
            web = null;
        }
        root.removeAllViews();

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        int padH = dp(120);
        col.setPadding(padH, 0, padH, 0);

        TextView title = new TextView(this);
        title.setText("连接飞牛");
        title.setTextColor(TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText("只填地址，不填账号密码。登录一次后会自动记住。");
        sub.setTextColor(TEXT2);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(14);
        col.addView(sub, slp);

        final EditText et = new EditText(this);
        et.setHint("192.168.1.10:5666");
        et.setHintTextColor(TEXT3);
        et.setTextColor(TEXT);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        et.setBackground(box(0xFF1E242E, 0xFF2A3340));
        et.setPadding(dp(22), dp(16), dp(22), dp(16));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(760), ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = dp(38);
        col.addView(et, elp);
        et.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                InputMethodManager imm =
                        (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
            }
        });

        final Button btn = new Button(this);
        btn.setText("连接并全屏打开");
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        btn.setBackground(box(BLUE, 0));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(420), dp(76));
        blp.topMargin = dp(30);
        col.addView(btn, blp);

        TextView hint = new TextView(this);
        hint.setText("ENTER 直接连接  ·  MENU 菜单  ·  遥控器 OK 会打开输入法");
        hint.setTextColor(TEXT3);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(24);
        col.addView(hint, hlp);

        View.OnClickListener connect = new View.OnClickListener() {
            public void onClick(View v) {
                String raw = et.getText().toString().trim();
                if (raw.length() == 0) {
                    Toast.makeText(MainActivity.this, "请填写地址", Toast.LENGTH_SHORT).show();
                    return;
                }
                String url = normalize(raw);
                sp.edit().putString(KEY_URL, url).apply();
                InputMethodManager imm =
                        (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(et.getWindowToken(), 0);
                openBrowser(url);
            }
        };
        btn.setOnClickListener(connect);
        et.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN)) {
                    connect.onClick(v);
                    return true;
                }
                return false;
            }
        });

        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        et.requestFocus();
    }

    private String normalize(String raw) {
        String s = raw.trim();
        if (!(s.startsWith("http://") || s.startsWith("https://"))) s = "http://" + s;
        String rest = s.substring(s.indexOf("://") + 3);
        int slash = rest.indexOf('/');
        String hostPart = slash < 0 ? rest : rest.substring(0, slash);
        String tail = slash < 0 ? "" : rest.substring(slash);
        if (hostPart.indexOf(':') < 0) {
            hostPart = hostPart + (s.startsWith("https://") ? ":5667" : ":5666");
        }
        return s.substring(0, s.indexOf("://") + 3) + hostPart + tail;
    }

    // ------------------------------------------------------------------ browser

    private void openBrowser(String url) {
        setupMode = false;
        root.removeAllViews();
        applySafeArea();

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setSupportMultipleWindows(false);

        web.setBackgroundColor(0xFF0B0E13);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String u) {
                return false;
            }

            @Override
            public void onPageFinished(WebView v, String u) {
                js(JS_POINTER);
                cursorShown = false;
            }

            @Override
            public void onReceivedSslError(WebView v, final SslErrorHandler handler, SslError error) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("无法验证此服务器的身份")
                        .setMessage("继续访问可能不安全。仅在确认这是你自己的设备时继续。")
                        .setPositiveButton("继续", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                handler.proceed();
                            }
                        })
                        .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                handler.cancel();
                            }
                        })
                        .show();
            }
        });

        chrome = new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customCallback = callback;
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
                hideToolbar();
                goFullscreen();
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                root.removeView(customView);
                customView = null;
                web.setVisibility(View.VISIBLE);
                if (customCallback != null) {
                    customCallback.onCustomViewHidden();
                    customCallback = null;
                }
                goFullscreen();
            }
        };
        web.setWebChromeClient(chrome);

        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildToolbar();
        web.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                // 鼠标点击同样算“在用鼠标”，避免注入光标误显示
                lastMouseAt = System.currentTimeMillis();
                if (cursorShown) hideCursor();
                return false;
            }
        });
        web.loadUrl(url);
        web.requestFocus();
    }

    private void applySafeArea() {
        // 老电视有 3%~5% 过扫描，整体缩到 96% 防止被裁边
        root.setScaleX(0.96f);
        root.setScaleY(0.96f);
    }

    private void js(final String code) {
        if (web == null) return;
        web.post(new Runnable() {
            public void run() {
                try {
                    web.evaluateJavascript(code, null);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    // ------------------------------------------------------------------ toolbar

    private void buildToolbar() {
        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER);
        toolbar.setBackground(box(0xEE131A24, 0xFF2C3646));
        toolbar.setPadding(dp(10), dp(8), dp(10), dp(8));
        toolbar.setVisibility(View.GONE);

        String[] labels = {"后退", "前进", "刷新", "主页", "右键", "菜单"};
        for (int i = 0; i < labels.length; i++) {
            final String label = labels[i];
            TextView t = new TextView(this);
            t.setText(label);
            t.setTextColor(TEXT2);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(20), dp(12), dp(20), dp(12));
            t.setFocusable(true);
            t.setBackgroundColor(Color.TRANSPARENT);
            t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                public void onFocusChange(View v, boolean has) {
                    v.setBackground(has ? box(0xFF22304A, 0xFFFFFFFF) : null);
                    if (has) v.setBackground(box(0xFF22304A, 0xFFFFFFFF));
                }
            });
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    onToolbarAction(label);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(6);
            lp.rightMargin = dp(6);
            toolbar.addView(t, lp);
        }

        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        flp.bottomMargin = dp(28);
        root.addView(toolbar, flp);
    }

    private void onToolbarAction(String label) {
        if ("后退".equals(label)) {
            if (web.canGoBack()) web.goBack();
        } else if ("前进".equals(label)) {
            if (web.canGoForward()) web.goForward();
        } else if ("刷新".equals(label)) {
            web.reload();
        } else if ("主页".equals(label)) {
            web.loadUrl(sp.getString(KEY_URL, ""));
        } else if ("右键".equals(label)) {
            js("window.__fnClick && window.__fnClick(2)");
        } else if ("菜单".equals(label)) {
            showMenu();
        }
    }

    private void showToolbar() {
        if (toolbar == null) return;
        toolbarVisible = true;
        toolbar.setVisibility(View.VISIBLE);
        toolbar.setAlpha(0f);
        toolbar.animate().alpha(1f).setDuration(150).start();
        if (toolbar.getChildCount() > 0) toolbar.getChildAt(0).requestFocus();
        hideCursor();
        handler.removeCallbacks(hideToolbarRunnable);
        handler.postDelayed(hideToolbarRunnable, 8000);
    }

    private void hideToolbar() {
        toolbarVisible = false;
        if (toolbar == null) return;
        toolbar.animate().alpha(0f).setDuration(150).withEndAction(new Runnable() {
            public void run() {
                if (!toolbarVisible && toolbar != null) toolbar.setVisibility(View.GONE);
                if (web != null) web.requestFocus();
            }
        }).start();
    }

    private final Runnable hideToolbarRunnable = new Runnable() {
        public void run() {
            if (toolbarVisible) hideToolbar();
        }
    };

    private void toggleToolbar() {
        if (toolbarVisible) hideToolbar();
        else showToolbar();
    }

    // ------------------------------------------------------------------ menu

    private void showMenu() {
        final String[] items = {
                "修改地址",
                "重新登录",
                "清除缓存",
                pointerMode ? "遥控器指针：开" : "遥控器指针：关",
                "关于",
                "退出"
        };
        new AlertDialog.Builder(this)
                .setTitle("菜单")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0:
                                sp.edit().remove(KEY_URL).apply();
                                showSetup();
                                break;
                            case 1:
                                CookieManager.getInstance().removeAllCookies(null);
                                CookieManager.getInstance().flush();
                                js("try{localStorage.clear();sessionStorage.clear();}catch(e){}");
                                web.clearCache(true);
                                web.reload();
                                break;
                            case 2:
                                web.clearCache(true);
                                Toast.makeText(MainActivity.this, "缓存已清除", Toast.LENGTH_SHORT).show();
                                break;
                            case 3:
                                pointerMode = !pointerMode;
                                if (!pointerMode) hideCursor();
                                break;
                            case 4:
                                Toast.makeText(MainActivity.this, aboutText(), Toast.LENGTH_LONG).show();
                                break;
                            default:
                                finish();
                                break;
                        }
                    }
                })
                .show();
    }

    private String aboutText() {
        String pkg = "";
        try {
            pkg = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
        }
        return "飞牛电视浏览器 " + pkg + "\nWebView: " + android.os.Build.VERSION.RELEASE;
    }

    // ------------------------------------------------------------------ keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (setupMode || web == null) return super.dispatchKeyEvent(e);

        final int kc = e.getKeyCode();
        final int action = e.getAction();

        if (kc == KeyEvent.KEYCODE_BACK) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (toolbarVisible) {
                    hideToolbar();
                } else if (customView != null) {
                    if (chrome != null) chrome.onHideCustomView();
                } else if (web.canGoBack()) {
                    web.goBack();
                } else {
                    confirmExit();
                }
            }
            return true;
        }

        if (kc == KeyEvent.KEYCODE_MENU || kc == KeyEvent.KEYCODE_SETTINGS
                || kc == KeyEvent.KEYCODE_INFO) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() > 0) {
                    if (pointerMode && !toolbarVisible) js("window.__fnClick && window.__fnClick(2)");
                } else {
                    toggleToolbar();
                }
            }
            return true;
        }

        if (toolbarVisible) return super.dispatchKeyEvent(e);

        if (kc == KeyEvent.KEYCODE_CHANNEL_UP || kc == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            if (action == KeyEvent.ACTION_DOWN) {
                int d = kc == KeyEvent.KEYCODE_CHANNEL_UP ? -3 : 3;
                js("window.__fnWheel && window.__fnWheel(" + d + ")");
            }
            return true;
        }

        if (kc == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || kc == KeyEvent.KEYCODE_MEDIA_PLAY
                || kc == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            if (action == KeyEvent.ACTION_DOWN) js(JS_PLAY_PAUSE);
            return true;
        }

        if (!pointerMode) return super.dispatchKeyEvent(e);

        int step = 16;
        if (e.getRepeatCount() > 2) step = 16 + Math.min(e.getRepeatCount(), 12) * 6;

        switch (kc) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (action == KeyEvent.ACTION_DOWN) js("window.__fnMove && window.__fnMove(-" + step + ",0)");
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (action == KeyEvent.ACTION_DOWN) js("window.__fnMove && window.__fnMove(" + step + ",0)");
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (action == KeyEvent.ACTION_DOWN) js("window.__fnMove && window.__fnMove(0,-" + step + ")");
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (action == KeyEvent.ACTION_DOWN) js("window.__fnMove && window.__fnMove(0," + step + ")");
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
                if (action == KeyEvent.ACTION_DOWN) {
                    if (e.getRepeatCount() == 0) handler.postDelayed(okLongPress, 700);
                    return true;
                }
                if (action == KeyEvent.ACTION_UP) {
                    handler.removeCallbacks(okLongPress);
                    if (!okLongFired) js("window.__fnClick && window.__fnClick(0)");
                    okLongFired = false;
                    return true;
                }
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private final Runnable okLongPress = new Runnable() {
        public void run() {
            okLongFired = true;
            showToolbar();
        }
    };

    // ------------------------------------------------------------------ mouse / cursor

    @Override
    public boolean onGenericMotionEvent(MotionEvent ev) {
        int src = ev.getSource();
        boolean mouse = (src & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                || ev.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE;
        if (mouse) {
            lastMouseAt = System.currentTimeMillis();
            if (cursorShown) hideCursor();
        }
        return super.onGenericMotionEvent(ev);
    }

    private void hideCursor() {
        cursorShown = false;
        js("window.__fnShow && window.__fnShow(false)");
    }

    private void tickCursor() {
        if (setupMode || web == null || !pointerMode || toolbarVisible) return;
        boolean idle = System.currentTimeMillis() - lastMouseAt > 4000;
        if (idle && !cursorShown) {
            cursorShown = true;
            js("window.__fnShow && window.__fnShow(true)");
        } else if (!idle && cursorShown) {
            hideCursor();
        }
    }

    private void confirmExit() {
        new AlertDialog.Builder(this)
                .setTitle("退出")
                .setMessage("要退出飞牛电视浏览器吗？")
                .setPositiveButton("退出", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        finish();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        if (setupMode) {
            super.onBackPressed();
            return;
        }
        dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 把 Cookie 落盘，保证下次开机还是登录状态
        if (web != null) {
            web.onPause();
            CookieManager.getInstance().flush();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        goFullscreen();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ injected JS

    private static final String JS_POINTER =
            "(function(){" +
            "if(window.__fnInit){window.__fnShow(true);return;}window.__fnInit=1;" +
            "var c=document.createElement('div');" +
            "c.style.cssText='position:fixed;left:0;top:0;width:22px;height:22px;border-radius:50%;" +
            "border:2px solid #fff;box-shadow:0 0 0 2px rgba(0,0,0,.6),0 0 14px rgba(47,123,255,.95);" +
            "background:rgba(47,123,255,.35);z-index:2147483647;pointer-events:none;display:none;" +
            "transition:transform .05s linear';" +
            "document.documentElement.appendChild(c);" +
            "window.__fnX=innerWidth/2;window.__fnY=innerHeight/2;" +
            "function paint(){c.style.transform='translate('+window.__fnX+'px,'+window.__fnY+'px) translate(-50%,-50%)';}" +
            "paint();" +
            "window.__fnMove=function(dx,dy){window.__fnX=Math.max(0,Math.min(innerWidth-1,window.__fnX+dx));" +
            "window.__fnY=Math.max(0,Math.min(innerHeight-1,window.__fnY+dy));paint();};" +
            "window.__fnShow=function(v){c.style.display=v?'block':'none';if(v)paint();};" +
            "window.__fnWheel=function(d){var el=document.elementFromPoint(window.__fnX,window.__fnY)||document.body;" +
            "try{el.dispatchEvent(new WheelEvent('wheel',{bubbles:true,cancelable:true,deltaY:d*120,clientX:window.__fnX,clientY:window.__fnY}));}catch(e){}};" +
            "var lastT=0,lastX=0,lastY=0;" +
            "function fire(el,t,o){try{" +
            "if(t.indexOf('pointer')===0&&typeof PointerEvent==='function'){el.dispatchEvent(new PointerEvent(t,o));}" +
            "else{el.dispatchEvent(new MouseEvent(t,o));}}catch(e){}}" +
            "window.__fnClick=function(btn){" +
            "var x=window.__fnX,y=window.__fnY;" +
            "var el=document.elementFromPoint(x,y);if(!el)return;" +
            "var o={bubbles:true,cancelable:true,clientX:x,clientY:y,button:btn,buttons:btn===0?1:2,view:window};" +
            "fire(el,'pointerover',o);fire(el,'mouseover',o);fire(el,'pointermove',o);fire(el,'mousemove',o);" +
            "fire(el,'pointerdown',o);fire(el,'mousedown',o);fire(el,'pointerup',o);fire(el,'mouseup',o);" +
            "if(btn===2){fire(el,'contextmenu',o);return;}" +
            "fire(el,'click',o);" +
            "var now=Date.now();" +
            "if(now-lastT<400&&Math.abs(x-lastX)<8&&Math.abs(y-lastY)<8){fire(el,'dblclick',o);}" +
            "lastT=now;lastX=x;lastY=y;" +
            "};" +
            "window.__fnShow(true);" +
            "})();";

    private static final String JS_PLAY_PAUSE =
            "(function(){var v=document.querySelector('video');if(!v)return;" +
            "if(v.paused){v.play();}else{v.pause();}})();";
}
