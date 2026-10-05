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

    private EditText editPort;
    private TextView textRootfsInfo;
    private LinearLayout mountList;
    private Switch switchKeepAlive;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        app = DshApp.get(this);

        editPort = (EditText) findViewById(R.id.editPort);
        textRootfsInfo = (TextView) findViewById(R.id.textRootfsInfo);
        mountList = (LinearLayout) findViewById(R.id.mountList);
        switchKeepAlive = (Switch) findViewById(R.id.switchKeepAlive);

        editPort.setText(Integer.toString(app.port()));
        switchKeepAlive.setChecked(app.keepAlive());
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
        ((Button) findViewById(R.id.btnAddMount)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
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
    }

    // ---- directory mounts -------------------------------------------------

    /** One row per configured mount: enable toggle, paths, and a delete button. */
    private void renderMounts() {
        mountList.removeAllViews();
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

    /** Ask for a host directory (with presets) and a guest mount point. */
    private void addMount() {
        final String[] presets = {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        .getAbsolutePath(),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                        .getAbsolutePath(),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                        .getAbsolutePath(),
                Environment.getExternalStorageDirectory().getAbsolutePath(),
        };
        final String[] labels = {
                "Download", "Documents", "DCIM", "内部存储根目录", "手动输入…",
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
        input.setHint("/mnt/phone");
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
                        if (Mount.prepareGuestDir(app.installer().rootfsDir(), guest, reason) == null) {
                            toast(reason.toString());
                            return;
                        }
                        List<Mount> mounts = app.mounts();
                        mounts.add(new Mount(hostPath, guest, true));
                        app.setMounts(mounts);
                        renderMounts();
                    }
                })
                .show();
    }

    // ---- storage permission ----------------------------------------------

    /**
     * Binding a shared-storage directory needs "all files access" on API 30+;
     * the runtime grant alone is not enough there. Both paths lead the user to
     * the system surface that actually grants it.
     */
    private void requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                toast(getString(R.string.settings_mount_granted));
                return;
            }
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[] { Manifest.permission.READ_EXTERNAL_STORAGE }, REQ_STORAGE);
        }
    }

    private void updateGrantButton() {
        Button button = (Button) findViewById(R.id.btnGrantStorage);
        boolean granted;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            granted = Environment.isExternalStorageManager();
        } else {
            granted = checkCallingOrSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        button.setText(granted
                ? R.string.settings_mount_granted
                : R.string.settings_mount_grant);
        button.setEnabled(!granted);
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
                "Debian 根文件系统：%s\n占用空间：%.1f MB\n服务地址：http://127.0.0.1:%d\n应用版本：%s",
                installed ? "已安装" : "未安装", bytes / 1048576.0, app.port(), version));
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
