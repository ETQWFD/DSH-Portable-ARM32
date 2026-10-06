// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 cyf112233
package io.github.cyf112233.portable;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import io.github.cyf112233.portable.core.Abi;
import io.github.cyf112233.portable.core.ProotLauncher;
import io.github.cyf112233.portable.core.RootfsInstaller;

/**
 * The single screen of the app: it installs the guest if needed, starts the
 * server under PRoot, and hands the tokenised URL to a WebView.
 *
 * The WebView is a browser, not a shell: the page it shows is the ordinary
 * dsh web surface, reached over loopback exactly as a desktop browser would.
 */
public class MainActivity extends Activity implements ProotLauncher.Listener {

    private static final String TAG = "DshMain";

    private static final int REQ_SETTINGS = 1;
    private static final int STATUS_BAR_DP = 50;

    private DshApp app;
    private ProotLauncher launcher;
    private RootfsInstaller installer;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuffer = new StringBuilder();

    private View statusBar;
    private View statusDot;
    private TextView statusText;
    private WebView webView;
    private View splash;
    private TextView splashHint;
    private TextView splashLog;
    private ScrollView splashLogScroll;
    private ProgressBar splashProgress;
    private Button btnPrimary;
    private Button btnSecondary;

    private boolean activityVisible;

    /** Status colours are chosen once instead of re-resolved on every update. */
    private enum Dot {
        IDLE, OK, WARN, ERR
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        app = DshApp.get(this);
        launcher = app.launcher();
        installer = app.installer();

        statusBar = findViewById(R.id.statusBar);
        statusDot = findViewById(R.id.statusDot);
        statusText = (TextView) findViewById(R.id.statusText);
        webView = (WebView) findViewById(R.id.webView);
        splash = findViewById(R.id.splash);
        splashHint = (TextView) findViewById(R.id.splashHint);
        splashLog = (TextView) findViewById(R.id.splashLog);
        splashLogScroll = (ScrollView) findViewById(R.id.splashLogScroll);
        splashProgress = (ProgressBar) findViewById(R.id.splashProgress);
        btnPrimary = (Button) findViewById(R.id.btnPrimary);
        btnSecondary = (Button) findViewById(R.id.btnSecondary);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        configureWebView();

        findViewById(R.id.btnConsole).setOnClickListener(new OpenTerminal());
        // The title is the settings affordance. A tap rather than a long press:
        // the first-run hint teaches it once, and a tap is what people try first.
        findViewById(R.id.titleText).setOnClickListener(new OpenSettings());
        btnPrimary.setOnClickListener(new PrimaryAction());
        btnSecondary.setOnClickListener(new RestartAction());

        // A server left running across an Activity recreation is reused rather
        // than restarted; only a cold start needs the full sequence.
        if (launcher.isRunning() && launcher.uiUrl() != null) {
            Log.i(TAG, "reusing running server at " + launcher.uiUrl());
            showWebView(launcher.uiUrl());
        } else {
            startFlow();
        }
    }

    /**
     * Explains the long-press gesture once, the first time the UI is usable.
     * The bar no longer carries a settings button, so this is the only pointer
     * the user gets to the mount and keep-alive configuration.
     */
    private void maybeShowHint() {
        if (app.hintShown()) {
            return;
        }
        View content = getLayoutInflater().inflate(R.layout.dialog_hint, null);
        final CheckBox neverShow = (CheckBox) content.findViewById(R.id.checkNeverShow);
        new AlertDialog.Builder(this)
                .setTitle(R.string.hint_title)
                .setMessage(R.string.hint_message)
                .setView(content)
                .setPositiveButton(R.string.hint_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // Only a ticked box silences it for good; dismissing keeps
                        // the reminder for the next launch.
                        if (neverShow.isChecked()) {
                            app.markHintShown();
                        }
                        dialog.dismiss();
                    }
                })
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityVisible = true;
        // The launcher is process-wide and may have moved on while this activity was
        // in the background (the server finished starting, or exited). Re-reading its
        // state here is what stops the header from sitting on a stale message such as
        // "正在检查环境…" after returning from another app.
        refreshFromLauncher();
    }

    /**
     * Brings the header, splash text and buttons in line with the launcher.
     *
     * Called on every resume. It never starts or stops anything: deciding that is the
     * user's call, and a resume is not a reason to relaunch a server.
     */
    private void refreshFromLauncher() {
        if (launcher.isRunning()) {
            if (launcher.uiUrl() != null) {
                setStatus(Dot.OK, getString(R.string.status_running));
                if (webView.getVisibility() != View.VISIBLE) {
                    showWebView(launcher.uiUrl());
                }
                btnSecondary.setVisibility(View.VISIBLE);
                btnSecondary.setText(R.string.btn_restart);
            } else {
                setStatus(Dot.WARN, getString(R.string.status_starting));
            }
            btnPrimary.setText(R.string.btn_stop);
            btnPrimary.setEnabled(true);
            return;
        }
        // Not running: say so plainly rather than leaving the last message up.
        if (installer.isInstalled()) {
            if (!Abi.is64Bit()) {
                setStatus(Dot.IDLE, "终端就绪（32 位 ARM）");
                btnPrimary.setText(R.string.btn_terminal);
            } else {
                setStatus(Dot.IDLE, getString(R.string.status_stopped));
                btnPrimary.setText(R.string.btn_start);
            }
        } else {
            setStatus(Dot.IDLE, getString(R.string.status_checking));
            btnPrimary.setText(R.string.btn_start);
        }
        btnPrimary.setEnabled(true);
        btnSecondary.setVisibility(View.GONE);
    }

    @Override
    protected void onPause() {
        super.onPause();
        activityVisible = false;
    }

    @Override
    protected void onDestroy() {
        // The server deliberately outlives the Activity; stopping it here would
        // kill the session whenever Android recreates the screen.
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        // The server deliberately keeps running when the screen closes.
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SETTINGS && resultCode == RESULT_OK) {
            boolean reinstall = data != null
                    && data.getBooleanExtra(SettingsActivity.EXTRA_REINSTALL, false);
            boolean restart = data != null
                    && data.getBooleanExtra(SettingsActivity.EXTRA_RESTART_SERVICE, false);
            if (reinstall || restart) {
                // Both cases want a fresh guest: one because the tree was deleted,
                // the other because bind mounts only exist inside a new process.
                restartServer();
            } else {
                toast("设置已保存，重启服务后生效");
            }
        }
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        // The guest page is authored for a desktop-width frame; the mobile
        // plugin narrows it, so the viewport must stay a real device viewport.
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        CookieManager.getInstance().setAcceptCookie(true);

        WebView.setWebContentsDebuggingEnabled(true);
        webView.setWebViewClient(new LocalOnlyClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.setBackgroundColor(Color.parseColor("#15171C"));
    }

    /** Keeps loopback traffic in-app and hands anything else to the system. */
    private final class LocalOnlyClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri target = request.getUrl();
            String url = target.toString();
            if (url.contains("127.0.0.1") || url.contains("localhost")) {
                return false;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, target));
            } catch (Exception e) {
                Log.w(TAG, "no activity for " + url);
            }
            return true;
        }
    }

    private final class OpenSettings implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            startActivityForResult(new Intent(MainActivity.this, SettingsActivity.class),
                    REQ_SETTINGS);
        }
    }

    /** Opens the interactive Debian shell. */
    private final class OpenTerminal implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            startActivity(new Intent(MainActivity.this, TerminalActivity.class));
        }
    }

    private final class PrimaryAction implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            if (launcher.isRunning()) {
                stopServer();
            } else if (!installer.isInstalled()) {
                startFlow();
            } else {
                launchServer();
            }
        }
    }

    private final class RestartAction implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            restartServer();
        }
    }

    /**
     * Repaints the first-run progress UI. A static nested class rather than an
     * anonymous one holding an implicit outer reference: d8 rejects an anonymous
     * class nested inside an inner class of this source level, and the explicit
     * reference is clearer anyway.
     */
    private static final class ApplyInstallProgress implements Runnable {
        private final MainActivity activity;
        private final String message;

        ApplyInstallProgress(MainActivity activity, String message) {
            this.activity = activity;
            this.message = message;
        }

        @Override
        public void run() {
            activity.splashHint.setText(message);
            activity.splashProgress.setIndeterminate(true);
        }
    }

    private static final class ApplyInstallDone implements Runnable {
        private final MainActivity activity;
        private final boolean success;
        private final String error;

        ApplyInstallDone(MainActivity activity, boolean success, String error) {
            this.activity = activity;
            this.success = success;
            this.error = error;
        }

        @Override
        public void run() {
            activity.btnPrimary.setEnabled(true);
            activity.btnPrimary.setText(R.string.btn_start);
            if (success) {
                activity.appendLog("根文件系统安装完成。");
                if (Abi.is64Bit()) {
                    activity.setStatus(Dot.OK, activity.getString(R.string.status_installed));
                    activity.launchServer();
                } else {
                    activity.openTerminalOnly(true);
                }
            } else {
                activity.setStatus(Dot.ERR, activity.getString(R.string.status_error));
                activity.splashHint.setText("安装失败：" + error);
                activity.appendLog("安装失败：" + error);
            }
        }
    }

    /**
     * Bridges the installer's worker-thread callbacks onto the UI thread. Only
     * this listener touches the splash widgets while installation runs.
     */
    private static final class InstallListener implements RootfsInstaller.Listener {
        private final MainActivity activity;

        InstallListener(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public void onProgress(String message, int entries) {
            activity.main.post(new ApplyInstallProgress(activity, message));
        }

        @Override
        public void onDone(boolean success, String error) {
            activity.main.post(new ApplyInstallDone(activity, success, error));
        }
    }

    /** Runs the blocking extraction off the UI thread. */
    private static final class InstallWorker implements Runnable {
        private final MainActivity activity;

        InstallWorker(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public void run() {
            activity.installer.install(new InstallListener(activity));
        }
    }

    /** Decide whether this is a first run, then install and/or launch. */
    private void startFlow() {
        showSplash();
        setStatus(Dot.IDLE, getString(R.string.status_checking));

        if (installer.isInstalled()) {
            if (Abi.is64Bit()) {
                launchServer();
            } else {
                openTerminalOnly(false);
            }
            return;
        }

        setStatus(Dot.WARN, getString(R.string.status_installing));
        splashHint.setText(R.string.status_installing);
        splashProgress.setIndeterminate(true);
        appendLog("首次启动：正在从 APK 解压 Debian 根文件系统（约 1–2 分钟）…");
        btnPrimary.setEnabled(false);
        btnPrimary.setText("安装中…");

        Thread worker = new Thread(new InstallWorker(this), "rootfs-install");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * The 32-bit (ARMv7) experience: dsh's AI service has no linux-arm native
     * build, so instead of a WebView we hand the user the fully working Debian
     * terminal (bash/apt/Node 22). Called after install or on the first launch
     * of an already-installed 32-bit image.
     */
    private void openTerminalOnly(boolean justInstalled) {
        showSplash();
        setStatus(Dot.IDLE, "终端就绪（32 位 ARM）");
        splashProgress.setIndeterminate(false);
        splashHint.setText("这是 32 位 ARM 设备：AI 服务仅支持 64 位，终端可正常使用。");
        appendLog(justInstalled
                ? "32 位环境就绪。点右上角「终端」进入 Debian（bash / apt / Node 22）。"
                : "32 位终端版：点右上角「终端」进入 Debian。AI 服务仅支持 64 位设备。");
        btnPrimary.setText(R.string.btn_terminal);
        btnPrimary.setEnabled(true);
        btnSecondary.setVisibility(View.GONE);
        // On this ABI the primary button is the terminal launcher.
        btnPrimary.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, TerminalActivity.class));
            }
        });
    }

    private void launchServer() {
        // dsh cannot run on linux-arm (its native addon fails closed there).
        if (!Abi.is64Bit()) {
            openTerminalOnly(false);
            return;
        }
        setStatus(Dot.WARN, getString(R.string.status_starting));
        splashHint.setText(R.string.status_starting);
        splashProgress.setIndeterminate(true);
        btnPrimary.setEnabled(false);
        btnPrimary.setText("启动中…");
        btnSecondary.setVisibility(View.GONE);
        appendLog(app.launcher().storageAccessReport());
        appendLog("启动 PRoot → Debian → dsh web（端口 " + app.port() + "）…");

        try {
            int actualPort = launcher.start(app.port(), this);
            if (actualPort != app.port()) {
                appendLog("端口 " + app.port() + " 已被占用，改用 " + actualPort + "。");
            }
        } catch (Exception e) {
            Log.e(TAG, "launch failed", e);
            setStatus(Dot.ERR, getString(R.string.status_error));
            splashHint.setText("启动失败：" + e.getMessage());
            appendLog("启动失败：" + e.getMessage());
            btnPrimary.setEnabled(true);
            btnPrimary.setText(R.string.btn_start);
        }
    }

    /** Relaunch after a delay so the old process has time to release the port. */
    private final class Relaunch implements Runnable {
        @Override
        public void run() {
            launchServer();
        }
    }

    private void restartServer() {
        appendLog("正在重启服务…");
        // The terminal session carries the binds it was started with, so it has to go
        // too; otherwise it would keep the old mount set alive next to the new server.
        TerminalActivity.closeAnySession();
        launcher.stop();
        KeepAliveService.stop(this);
        webView.setVisibility(View.GONE);
        showSplash();
        main.postDelayed(new Relaunch(), 1500);
    }

    private void stopServer() {
        TerminalActivity.closeAnySession();
        launcher.stop();
        KeepAliveService.stop(this);
        setStatus(Dot.IDLE, getString(R.string.status_stopped));
        appendLog("服务已停止。");
        btnPrimary.setText(R.string.btn_start);
        btnPrimary.setEnabled(true);
        btnSecondary.setVisibility(View.GONE);
        showSplash();
        splashHint.setText(R.string.status_stopped);
    }

    /** Appends one server log line on the UI thread. */
    private static final class ApplyLine implements Runnable {
        private final MainActivity activity;
        private final String text;

        ApplyLine(MainActivity activity, String text) {
            this.activity = activity;
            this.text = text;
        }

        @Override
        public void run() {
            activity.appendLog(text);
        }
    }

    @Override
    public void onLine(String line) {
        main.post(new ApplyLine(this, line));
    }

    /** Prints one mount's outcome and, for applied ones, asks the guest to confirm. */
    private final class ReportMounts implements Runnable {
        private final java.util.List<ProotLauncher.MountReport> reports;

        ReportMounts(java.util.List<ProotLauncher.MountReport> reports) {
            this.reports = reports;
        }

        @Override
        public void run() {
            int ok = 0;
            for (ProotLauncher.MountReport report : reports) {
                if (report.applied) {
                    ok++;
                    appendLog("挂载 " + report.hostPath + " → " + report.guestPath
                            + (report.detail.length() > 0 ? "（" + report.detail + "）" : ""));
                } else {
                    appendLog("挂载未生效：" + report.hostPath + " → " + report.guestPath
                            + "：" + report.detail);
                }
            }
            appendLog("共 " + reports.size() + " 项挂载，已应用 " + ok + " 项，正在校验…");
            // The app cannot see the guest's storage view, so ask the guest itself.
            launcher.probeMounts(reports, new MountProbeLines());
        }
    }

    /** Turns the probe's MOUNT_* lines into readable console output. */
    private final class MountProbeLines implements ProotLauncher.LineSink {
        @Override
        public void onLine(String line) {
            String text;
            if (line.startsWith("MOUNT_OK ")) {
                text = "校验通过：" + line.substring("MOUNT_OK ".length());
            } else if (line.startsWith("MOUNT_EMPTY ")) {
                text = "挂载已建立但目录为空：" + line.substring("MOUNT_EMPTY ".length());
            } else if (line.startsWith("MOUNT_LINK_OK ")) {
                text = "该目录支持硬链接，新建文件正常："
                        + line.substring("MOUNT_LINK_OK ".length());
            } else if (line.startsWith("MOUNT_NO_LINK ")) {
                text = "已自动启用兼容写入（该目录不支持硬链接）："
                        + line.substring("MOUNT_NO_LINK ".length());
            } else {
                text = "校验未通过：" + line.substring(Math.min(line.length(), 11));
            }
            final String message = text;
            main.post(new Runnable() {
                @Override
                public void run() {
                    appendLog(message);
                }
            });
        }
    }

    @Override
    public void onMounts(java.util.List<ProotLauncher.MountReport> reports) {
        main.post(new ReportMounts(reports));
    }

    /** Wraps the ready URL so the WebView is only touched from the UI thread. */
    private final class ShowUrl implements Runnable {
        private final String url;

        ShowUrl(String url) {
            this.url = url;
        }

        @Override
        public void run() {
            appendLog("服务已就绪，正在加载界面…");
            setStatus(Dot.OK, getString(R.string.status_running));
            showWebView(url);
            if (MainActivity.this.keepAliveEnabled()) {
                KeepAliveService.start(MainActivity.this, "运行中");
            }
            MainActivity.this.maybeShowHint();
        }
    }

    @Override
    public void onUrl(String url) {
        Log.i(TAG, "server ready: " + url);
        main.post(new ShowUrl(url));
    }

    /** Reflects an unexpected server exit without leaving a dead WebView up. */
    private final class HandleExit implements Runnable {
        private final int code;

        HandleExit(int code) {
            this.code = code;
        }

        @Override
        public void run() {
            appendLog("服务进程已退出（code=" + code + "）。");
            if (code != 0) {
                setStatus(Dot.ERR, getString(R.string.status_error) + " (exit " + code + ")");
                showSplash();
                splashHint.setText("服务异常退出（code=" + code + "）。点「启动」重试，或查看控制台。");
            } else {
                setStatus(Dot.IDLE, getString(R.string.status_stopped));
                showSplash();
                splashHint.setText(R.string.status_stopped);
            }
            btnPrimary.setText(R.string.btn_start);
            btnPrimary.setEnabled(true);
        }
    }

    @Override
    public void onExit(int code) {
        main.post(new HandleExit(code));
    }

    private void showSplash() {
        splash.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);
        splashProgress.setIndeterminate(true);
    }

    private void showWebView(String url) {
        webView.setVisibility(View.VISIBLE);
        splash.setVisibility(View.GONE);
        webView.loadUrl(url);
        btnSecondary.setVisibility(View.VISIBLE);
        btnSecondary.setText(R.string.btn_restart);
    }

    /** Scrolls a ScrollView to its bottom on the next layout pass. */
    private static final class ScrollToBottom implements Runnable {
        private final ScrollView view;

        ScrollToBottom(ScrollView view) {
            this.view = view;
        }

        @Override
        public void run() {
            view.fullScroll(View.FOCUS_DOWN);
        }
    }

    private void appendLog(String line) {
        // Keep the on-screen log bounded; the ring buffer holds the real history.
        if (logBuffer.length() > 24000) {
            logBuffer.delete(0, logBuffer.length() - 12000);
        }
        logBuffer.append(line).append('\n');
        splashLog.setText(logBuffer.toString());
        splashLogScroll.post(new ScrollToBottom(splashLogScroll));
    }

    private void setStatus(Dot dot, String text) {
        statusText.setText(text);
        switch (dot) {
            case OK:
                statusDot.setBackgroundResource(R.drawable.dot_ok);
                break;
            case WARN:
                statusDot.setBackgroundResource(R.drawable.dot_warn);
                break;
            case ERR:
                statusDot.setBackgroundResource(R.drawable.dot_err);
                break;
            default:
                statusDot.setBackgroundResource(R.drawable.dot_idle);
                break;
        }
    }

    private boolean keepAliveEnabled() {
        return app.keepAlive();
    }

    private void toast(String message) {
        if (activityVisible) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
    }
}
