// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 cyf112233
package io.github.cyf112233.portable;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import io.github.cyf112233.portable.core.Mount;
import io.github.cyf112233.portable.core.RootfsInstaller;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Port and rootfs maintenance.
 *
 * The API key is deliberately not configured here: the ordinary dsh web Models
 * page already writes the credential store, so this screen only carries the
 * settings that have to exist before the UI can come up.
 */
public class SettingsActivity extends Activity {

    public static final String EXTRA_REINSTALL = "reinstall";

    private DshApp app;
    private static final int REQ_STORAGE = 2;

    /**
     * The prefix every acceptable host path must live under, as the platform itself
     * names it: /storage/emulated/&lt;userId&gt;.
     *
     * Read off the shared-storage path rather than from an API, so a secondary user
     * or a work profile is accepted on its own terms instead of being compared
     * against a hard-coded 0.
     */
    private static String sharedStorageRoot() {
        String path = Environment.getExternalStorageDirectory().getAbsolutePath();
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private EditText editPort;
    private TextView textRootfsInfo;
    private LinearLayout mountList;
    private TextView mountNotice;
    private Button addMountButton;
    private Switch switchKeepAlive;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        app = DshApp.get(this);

        editPort = (EditText) findViewById(R.id.editPort);
        textRootfsInfo = (TextView) findViewById(R.id.textRootfsInfo);
        mountList = (LinearLayout) findViewById(R.id.mountList);
        mountNotice = (TextView) findViewById(R.id.mountNotice);
        addMountButton = (Button) findViewById(R.id.btnAddMount);
        switchKeepAlive = (Switch) findViewById(R.id.switchKeepAlive);

        editPort.setText(Integer.toString(app.port()));
        switchKeepAlive.setChecked(app.keepAlive());
        TextView subtitle = (TextView) findViewById(R.id.settingsSubtitle);
        if (app.launcher().isRunning() && app.launcher().uiUrl() != null) {
            subtitle.setText("服务运行中；修改端口或挂载后请重启服务");
        }
        renderRootfsInfo();
        renderMounts();

        ((Button) findViewById(R.id.btnSave)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        ((Button) findViewById(R.id.btnReset)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editPort.setText(Integer.toString(DshApp.DEFAULT_PORT));
            }
        });
        ((Button) findViewById(R.id.btnReinstall)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmReinstall();
            }
        });
        addMountButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Re-checked at the point of use: the user may have just returned
                // from the system grant screen.
                if (!app.hasStorageAccess()) {
                    requestStorageAccess();
                    toast(getString(R.string.mount_needs_access));
                    return;
                }
                addMount();
            }
        });
        ((Button) findViewById(R.id.btnGrantStorage)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestStorageAccess();
            }
        });
        ((Button) findViewById(R.id.btnAbout)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, AboutActivity.class));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateGrantButton();
        // The user may have just granted access in the system screen.
        if (addMountButton != null) {
            renderMounts();
        }
    }

    // ---- directory mounts -------------------------------------------------

    /** One row per configured mount: enable toggle, paths, and a delete button. */
    private void renderMounts() {
        mountList.removeAllViews();

        // Without all-files access a bind over shared storage reads as an empty
        // directory, so the feature is closed off rather than left to fail quietly.
        boolean allowed = app.hasStorageAccess();
        addMountButton.setEnabled(allowed);
        addMountButton.setAlpha(allowed ? 1f : 0.5f);
        mountNotice.setVisibility(allowed ? View.GONE : View.VISIBLE);
        if (!allowed) {
            mountNotice.setText(R.string.mount_needs_access_long);
        }

        final List<Mount> mounts = app.mounts();
        if (mounts.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.settings_mount_none);
            empty.setTextSize(12f);
            empty.setTextColor(getResources().getColor(R.color.bar_fg_dim));
            mountList.addView(empty);
            return;
        }
        for (int i = 0; i < mounts.size(); i++) {
            final Mount mount = mounts.get(i);
            final int index = i;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));

            CheckBox box = new CheckBox(this);
            box.setChecked(mount.enabled);
            box.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    List<Mount> current = app.mounts();
                    if (index < current.size()) {
                        Mount old = current.get(index);
                        current.set(index, new Mount(old.hostPath, old.guestPath, isChecked));
                        app.setMounts(current);
                    }
                }
            });
            row.addView(box);

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            texts.setLayoutParams(textParams);

            TextView host = new TextView(this);
            host.setText(mount.hostPath);
            host.setTextSize(12f);
            host.setTextColor(getResources().getColor(R.color.bar_fg));

            TextView arrow = new TextView(this);
            arrow.setText("→ " + mount.guestPath + (mount.isUsable() ? "" : "   （不可用）"));
            arrow.setTextSize(11f);
            arrow.setTextColor(getResources().getColor(
                    mount.isUsable() ? R.color.bar_fg_dim : R.color.err));

            texts.addView(host);
            texts.addView(arrow);
            row.addView(texts);

            Button remove = new Button(this);
            remove.setText("删除");
            remove.setTextSize(11f);
            remove.setMinWidth(0);
            remove.setPadding(dp(10), 0, dp(10), 0);
            remove.setTextColor(getResources().getColor(R.color.err));
            remove.setBackgroundColor(0x00000000);
            remove.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    List<Mount> current = app.mounts();
                    if (index < current.size()) {
                        current.remove(index);
                        app.setMounts(current);
                        renderMounts();
                    }
                }
            });
            row.addView(remove);

            mountList.addView(row);
        }
    }

    /**
     * Ask for a host directory and a guest mount point.
     *
     * Only shared storage is offered: it is the one place the app can reach with the
     * permissions it has, and the guest path is constrained to /root/<name> because
     * binding over /root itself would hide the home directory dsh lives in.
     */
    private void addMount() {
        final String external = Environment.getExternalStorageDirectory().getAbsolutePath();
        final String[] presets = {
                external,
                new File(external, Environment.DIRECTORY_DOWNLOADS).getAbsolutePath(),
                new File(external, Environment.DIRECTORY_DOCUMENTS).getAbsolutePath(),
                new File(external, Environment.DIRECTORY_DCIM).getAbsolutePath(),
        };
        final String[] labels = {
                "共享存储根目录", "Download", "Documents", "DCIM", "手动输入…",
        };

        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_mount_add)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which < presets.length) {
                            askGuestPath(presets[which]);
                        } else {
                            askHostPath();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void askHostPath() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("/storage/emulated/0/Download");
        input.setText(Environment.getExternalStorageDirectory().getAbsolutePath());
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_mount_host)
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("下一步", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        askGuestPath(input.getText().toString().trim());
                    }
                })
                .show();
    }

    private void askGuestPath(final String hostPath) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("/root/123");
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_mount_guest)
                .setMessage(hostPath)
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("添加", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String guest = input.getText().toString().trim();
                        if (!guest.startsWith("/")) {
                            guest = "/" + guest;
                        }
                        File host = new File(hostPath);
                        if (!host.isDirectory()) {
                            toast("手机目录不存在：" + hostPath);
                            return;
                        }
                        // The guest path must be empty, or the bind would hide the
                        // files the rootfs keeps there.
                        StringBuilder reason = new StringBuilder();
                        String error = validateMount(hostPath, guest);
                        if (error != null) {
                            toast(error);
                            return;
                        }
                        StringBuilder notice = new StringBuilder();
                        if (Mount.prepareGuestDir(app.installer().rootfsDir(), guest,
                                reason, notice) == null) {
                            toast(reason.toString());
                            return;
                        }
                        if (notice.length() > 0) {
                            toast(notice.toString());
                        }
                        List<Mount> mounts = app.mounts();
                        mounts.add(new Mount(hostPath, guest, true));
                        app.setMounts(mounts);
                        renderMounts();
                    }
                })
                .show();
    }

    /**
     * The two placement rules this screen enforces.
     *
     * Guest side: /root/<name>, never /root itself -- binding over the home
     * directory hides everything dsh keeps there, including its profile.
     *
     * Host side: the current user's shared storage and nothing else, because that is
     * the only tree all-files access covers; anything else would be rejected by the
     * platform at read time.
     *
     * @return null when the pair is acceptable, otherwise the reason to show
     */
    private String validateMount(String hostPath, String guestPath) {
        String host = hostPath == null ? "" : hostPath.trim();
        String guest = guestPath == null ? "" : guestPath.trim();

        if (!guest.startsWith("/root/") || guest.equals("/root/")
                || guest.substring("/root/".length()).replace("/", "").length() == 0) {
            return "Debian 内路径必须形如 /root/xxx，不能直接挂到 /root";
        }
        if (guest.contains("..")) {
            return "Debian 内路径不能包含 ..";
        }

        String shared = Environment.getExternalStorageDirectory().getAbsolutePath();
        if (!host.equals(shared) && !host.startsWith(shared + "/")) {
            return "只能挂载共享存储：" + shared + " 或其子目录";
        }
        // Belt and braces: the same rule stated in platform terms, with the trailing
        // separator so /storage/emulated/10 cannot pass as user 1.
        String userRoot = sharedStorageRoot();
        if (!host.equals(userRoot) && !host.startsWith(userRoot + "/")) {
            return "只能挂载当前用户的共享存储：" + userRoot;
        }
        if (host.contains("..")) {
            return "手机目录不能包含 ..";
        }
        return null;
    }

    // ---- storage permission ----------------------------------------------

    /**
     * Binding a shared-storage directory needs "all files access" on API 30+;
     * the runtime grant alone is not enough there. Both paths lead the user to
     * the system surface that actually grants it.
     */
    private void requestStorageAccess() {
        List<String> missing = app.missingStoragePermissions();
        if (missing.isEmpty()) {
            toast(getString(R.string.settings_mount_granted));
            return;
        }
        // Two different mechanisms, so ask in order: the ordinary runtime permission
        // can be requested here, while all-files access only exists on a system
        // settings screen.
        boolean needsLegacy = checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED;
        if (needsLegacy) {
            requestPermissions(new String[] { Manifest.permission.READ_EXTERNAL_STORAGE }, REQ_STORAGE);
            return;
        }
        openAllFilesAccessSettings();
    }

    private void openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception e2) {
                toast("无法打开系统授权页：" + e2.getMessage());
            }
        }
    }

    private void updateGrantButton() {
        Button button = (Button) findViewById(R.id.btnGrantStorage);
        List<String> missing = app.missingStoragePermissions();
        if (missing.isEmpty()) {
            button.setText(R.string.settings_mount_granted);
            button.setEnabled(false);
            return;
        }
        StringBuilder label = new StringBuilder(getString(R.string.settings_mount_grant));
        label.append("（缺：");
        for (int i = 0; i < missing.size(); i++) {
            if (i > 0) {
                label.append("、");
            }
            label.append(missing.get(i));
        }
        label.append("）");
        button.setText(label.toString());
        button.setEnabled(true);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_STORAGE) {
            updateGrantButton();
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private void renderRootfsInfo() {
        RootfsInstaller installer = app.installer();
        boolean installed = installer.isInstalled();
        long bytes = installer.installedBytes();
        String version;
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            version = "?";
        }
        textRootfsInfo.setText(String.format(Locale.US,
                "Debian 根文件系统  %s\n占用空间  %.0f MB\n服务地址  http://127.0.0.1:%d\n应用版本  %s",
                installed ? "已安装" : "未安装", bytes / 1048576.0, app.port(), version));
        TextView footer = (TextView) findViewById(R.id.textVersion);
        footer.setText("DSH Portable " + version + " · GPL-3.0-or-later");
    }

    private void save() {
        String portText = editPort.getText().toString().trim();
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            toast(getString(R.string.err_port));
            return;
        }
        if (port < 1024 || port > 65535) {
            toast(getString(R.string.err_port) + "：范围 1024–65535");
            return;
        }

        SharedPreferences.Editor editor = app.prefs().edit();
        editor.putInt(DshApp.KEY_PORT, port);
        editor.apply();
        app.setKeepAlive(switchKeepAlive.isChecked());

        setResult(RESULT_OK, new Intent());
        finish();
    }

    private void confirmReinstall() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_reinstall)
                .setMessage(R.string.settings_reinstall_note)
                .setNegativeButton("取消", null)
                .setPositiveButton("重新安装", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        app.installer().uninstall();
                        renderRootfsInfo();
                        Intent result = new Intent();
                        result.putExtra(EXTRA_REINSTALL, true);
                        setResult(RESULT_OK, result);
                        finish();
                    }
                })
                .show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
