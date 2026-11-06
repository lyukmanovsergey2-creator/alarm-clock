package ru.alarm.clean;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {

    private WebView webView;
    private NativeBridge bridge;
    private int appBackground = Color.parseColor("#0B0B0E");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /* Переносим настройки в device-protected storage (доступно до разблокировки). */
        AlarmScheduler.migrateToDeviceProtected(this);
        AlarmScheduler.logLine(this, "Приложение открыто");

        /* Ловим сбои в журнал: если приложение «моргает» при запуске —
           в журнале появится причина. */
        final Thread.UncaughtExceptionHandler previousHandler =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
                try {
                    AlarmScheduler.logLine(MainActivity.this, "СБОЙ в потоке «" + thread.getName() + "»: "
                            + error.getClass().getSimpleName()
                            + (error.getMessage() != null ? " " + error.getMessage() : ""));
                    StackTraceElement[] stack = error.getStackTrace();
                    int limit = Math.min(10, stack.length);
                    for (int i = 0; i < limit; i++) {
                        AlarmScheduler.logLine(MainActivity.this, "  у " + stack[i]);
                    }
                    /* Вся цепочка причин — часто настоящий источник не в первом исключении. */
                    Throwable cause = error.getCause();
                    int depth = 0;
                    while (cause != null && depth < 5) {
                        AlarmScheduler.logLine(MainActivity.this, "причина (" + (depth + 1) + "): "
                                + cause.getClass().getSimpleName()
                                + (cause.getMessage() != null ? " " + cause.getMessage() : ""));
                        StackTraceElement[] causeStack = cause.getStackTrace();
                        int causeLimit = Math.min(5, causeStack.length);
                        for (int i = 0; i < causeLimit; i++) {
                            AlarmScheduler.logLine(MainActivity.this, "  у " + causeStack[i]);
                        }
                        cause = cause.getCause();
                        depth++;
                    }
                } catch (Exception ignored) {
                }
                if (previousHandler != null) {
                    previousHandler.uncaughtException(thread, error);
                } else {
                    System.exit(2);
                }
            }
        });

        Window window = getWindow();
        window.setStatusBarColor(Color.parseColor("#0B0B0E"));
        window.setNavigationBarColor(Color.parseColor("#0B0B0E"));
        if (Build.VERSION.SDK_INT >= 29) {
            window.setNavigationBarContrastEnforced(false);
        }
        applyThemeColors(AlarmScheduler.prefs(this).getBoolean("lightTheme", false));

        webView = new WebView(this);
        webView.setBackgroundColor(appBackground);
        /* Записываем версию и процесс: видно, какое обновление сейчас работает. */
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            AlarmScheduler.logLine(this, "Запуск: версия " + v
                    + ", процесс " + android.os.Process.myPid());
        } catch (Exception ignored) {
        }

        webView.setLongClickable(false);
        webView.setHapticFeedbackEnabled(false);
        webView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                return true;
            }
        });

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);
        settings.setTextZoom(100);
        if (Build.VERSION.SDK_INT >= 26) {
            settings.setSafeBrowsingEnabled(false);
        }

        bridge = new NativeBridge(this, webView);
        webView.addJavascriptInterface(bridge, "NativeAlarm");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.loadUrl("file:///android_asset/index.html");
        setContentView(webView);

        requestNotificationPermission();
    }

    /** Окрашивает системные бары под выбранную тему (вызывается и из NativeBridge). */
    void applyThemeColors(boolean light) {
        appBackground = Color.parseColor(light ? "#F4F4F0" : "#0B0B0E");
        Window window = getWindow();
        window.setStatusBarColor(appBackground);
        window.setNavigationBarColor(appBackground);
        View decor = window.getDecorView();
        int flags = decor.getSystemUiVisibility()
                & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        if (light) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        decor.setSystemUiVisibility(flags);
        if (webView != null) {
            webView.setBackgroundColor(appBackground);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 11);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        /* После перезагрузки телефона будильники могли не восстановиться через BootReceiver.
           Проверяем при возврате на главный экран и переустанавливаем при необходимости
           (restore сам дозвонит пропущенные срабатывания и восстановит отложенный звонок). */
        try {
            AlarmScheduler.migrateToDeviceProtected(this);
            AlarmScheduler.restore(this, "onResume");
        } catch (Exception ignored) {
        }

        applyThemeColors(AlarmScheduler.prefs(this).getBoolean("lightTheme", false));

        /* Ежедневная фоновая проверка обновлений (если пользователь включил).
           Молчит во время звонка и никогда не мешает расписанию будильников. */
        try {
            UpdateChecker.maybeCheck(this);
        } catch (Exception ignored) {
        }

        if (AlarmService.running) {
            /* Пока будильник звонит, главный экран недоступен: показываем экран звонка. */
            try {
                Intent ring = new Intent(this, RingActivity.class);
                ring.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(ring);
            } catch (Exception ignored) {
            }
        }
        if (webView != null) {
            webView.onResume();
            webView.evaluateJavascript(
                    "window.__nativeResume && window.__nativeResume();", null);
        }
    }

    @Override
    protected void onPause() {
        /* Событие сворачивания/простоя приложения: помогает отличать
           «закрыл» от «упало» или «убило системой». */
        try {
            AlarmScheduler.logLine(this, "Приложение скрыто");
        } catch (Exception ignored) {
        }
        if (bridge != null) {
            bridge.stopPreview();
        }
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        try {
            AlarmScheduler.logLine(this, "Приложение в фоне (onStop)");
        } catch (Exception ignored) {
        }
        super.onStop();
    }

    void runJs(final String js) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    webView.evaluateJavascript(js, null);
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == NativeBridge.REQ_PICK_SOUND && resultCode == RESULT_OK
                && data != null && data.getData() != null && bridge != null) {
            bridge.onSoundPicked(data.getData());
        }
    }

    @Override
    public void onBackPressed() {
        if (webView == null) {
            super.onBackPressed();
            return;
        }
        /* Если открыта шторка настроек — закрываем её, иначе выходим. */
        webView.evaluateJavascript(
                "(window.__nativeBack && window.__nativeBack()) ? '1' : '0'",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        if (value == null || !value.contains("1")) {
                            MainActivity.super.onBackPressed();
                        }
                    }
                });
    }

    @Override
    protected void onDestroy() {
        try {
            AlarmScheduler.logLine(this, "Приложение закрыто (onDestroy)");
        } catch (Exception ignored) {
        }
        if (bridge != null) {
            bridge.release();
            bridge = null;
        }
        if (webView != null) {
            webView.removeJavascriptInterface("NativeAlarm");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
