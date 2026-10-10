package com.dsh.ba4dkeepalive;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Small settings screen. Everything is built in code so the module has no
 * dependency beyond the plain Android framework (keeps the APK tiny and the
 * build dependency-free).
 */
public class SettingsActivity extends Activity {

    private static final int PAD = 48;

    private TextView statusView;
    private TextView logView;
    private CheckBox enabledBox;
    private CheckBox restartBox;
    private CheckBox keepAliveBox;
    private CheckBox a11yBox;
    private CheckBox watchdogBox;
    private CheckBox respectBox;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, 2000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ControlFile.ensureDefaults();
        setContentView(buildUi());
        loadConfig();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ControlFile.reload();
        loadConfig();
        refreshStatus();
        handler.postDelayed(refresher, 2000L);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresher);
        super.onPause();
    }

    // ------------------------------------------------------------------- UI

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(PAD, PAD, PAD, PAD);
        scroll.addView(root);

        root.addView(title("BA4D 保活模块"));
        root.addView(hint("目标应用：com.miradesktop.ba4d（BA Spark 4 Droid）\n"
                + "作用：OverlayService 被关闭时自动重新启动。"));

        statusView = new TextView(this);
        statusView.setTextSize(13f);
        statusView.setPadding(0, PAD / 2, 0, PAD / 2);
        root.addView(statusView);

        root.addView(section("开关（写入 " + ControlFile.CONF + "）"));

        enabledBox = addCheck(root, "总开关：启用保活", ControlFile.KEY_ENABLED,
                "关闭后模块完全不做任何事。");
        restartBox = addCheck(root, "服务被关闭时自动重启 OverlayService", ControlFile.KEY_RESTART_SERVICE,
                "核心功能。会拦截 onDestroy / stopService / stopSelf 并重新 startForegroundService。");
        keepAliveBox = addCheck(root, "允许主动重启（keep_alive）", ControlFile.KEY_KEEP_ALIVE,
                "应用自己调用 stopSelf 时也重新拉起。若你会主动关闭悬浮窗，可关掉此项。");
        respectBox = addCheck(root, "尊重用户主动停止", ControlFile.KEY_RESPECT_USER_STOP,
                "检测到 stopSelf/stopService 时不立刻重启（推荐开启，避免和你手动关闭打架）。");
        a11yBox = addCheck(root, "恢复无障碍服务", ControlFile.KEY_RESTORE_A11Y,
                "应用更新后系统会清掉无障碍服务，模块会尝试写回设置（需要 Magisk 守护配合）。");
        watchdogBox = addCheck(root, "启用进程内看门狗", ControlFile.KEY_WATCHDOG,
                "每 3 秒检查一次服务状态，处理漏掉的关闭事件。");

        root.addView(section("操作"));

        root.addView(button("保存开关", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveConfig();
                Toast.makeText(SettingsActivity.this, "已保存", Toast.LENGTH_SHORT).show();
                refreshStatus();
            }
        }));

        root.addView(button("打开目标应用（并尝试重启服务）", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openTargetApp();
            }
        }));

        root.addView(button("打开无障碍设置", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Throwable t) {
                    Toast.makeText(SettingsActivity.this, "无法打开设置", Toast.LENGTH_SHORT).show();
                }
            }
        }));

        if (Build.VERSION.SDK_INT >= 33) {
            root.addView(button("授予通知权限（目标应用需要）", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                        i.putExtra(Settings.EXTRA_APP_PACKAGE, RestartController.TARGET_PKG);
                        startActivity(i);
                    } catch (Throwable t) {
                        Toast.makeText(SettingsActivity.this, "无法打开", Toast.LENGTH_SHORT).show();
                    }
                }
            }));
        }

        root.addView(section("运行日志（最近 300 行）"));

        logView = new TextView(this);
        logView.setTextSize(11f);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        root.addView(logView);

        root.addView(button("刷新日志", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logView.setText(KLog.tail());
            }
        }));

        root.addView(hint("\n配置与状态文件（由模块写入目标应用的私有目录）："
                + "\n  " + ControlFile.PRIVATE_DIR
                + "\n共用目录（守护脚本与 adb）："
                + "\n  " + ControlFile.DIR
                + "\n当前日志路径：" + String.valueOf(KLog.logPath())
                + "\nMagisk 守护日志：" + ControlFile.DIR + "/daemon.log"
                + "\n\n界面上的开关会通过广播交给模块写入真正的配置文件；"
                + "若目标应用未运行，开关只会记录在本地，等目标应用启动后再次点击保存即可生效。"));

        return scroll;
    }

    private TextView title(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(22f);
        tv.setGravity(Gravity.START);
        tv.setPadding(0, 0, 0, PAD / 2);
        return tv;
    }

    private TextView section(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15f);
        tv.setPadding(0, PAD, 0, PAD / 4);
        return tv;
    }

    private TextView hint(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setAlpha(0.75f);
        tv.setPadding(0, 0, 0, PAD / 4);
        return tv;
    }

    /** Adds a labelled checkbox plus its explanation to the container and returns the box. */
    private CheckBox addCheck(LinearLayout container, String label, final String key, String description) {
        final CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setPadding(PAD, PAD / 2, 0, 0);
        box.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                // Persist and hand the change to the module inside the target app.
                remember(key, isChecked);
                pushConfig();
            }
        });
        container.addView(box);

        TextView desc = hint(description);
        desc.setPadding(PAD, 0, 0, PAD / 4);
        container.addView(desc);
        return box;
    }

    private void remember(String key, boolean value) {
        getSharedPreferences(BootReceiver.PREFS, MODE_PRIVATE).edit()
                .putBoolean(key, value)
                .apply();
    }

    /** Sends the current switch state to the module, which owns the real config file. */
    private void pushConfig() {
        try {
            Intent intent = new Intent(ConfigReceiver.ACTION);
            intent.setPackage(RestartController.TARGET_PKG);
            intent.putExtra(ControlFile.KEY_ENABLED, enabledBox.isChecked());
            intent.putExtra(ControlFile.KEY_RESTART_SERVICE, restartBox.isChecked());
            intent.putExtra(ControlFile.KEY_KEEP_ALIVE, keepAliveBox.isChecked());
            intent.putExtra(ControlFile.KEY_RESPECT_USER_STOP, respectBox.isChecked());
            intent.putExtra(ControlFile.KEY_RESTORE_A11Y, a11yBox.isChecked());
            intent.putExtra(ControlFile.KEY_WATCHDOG, watchdogBox.isChecked());
            sendBroadcast(intent);
        } catch (Throwable t) {
            KLog.w("Settings", "pushConfig failed: " + KLog.describe(t));
        }
    }

    private Button button(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        return b;
    }

    // -------------------------------------------------------------- behaviour

    private void loadConfig() {
        SharedPreferences sp = getSharedPreferences(BootReceiver.PREFS, MODE_PRIVATE);
        enabledBox.setChecked(sp.getBoolean(ControlFile.KEY_ENABLED, true));
        restartBox.setChecked(sp.getBoolean(ControlFile.KEY_RESTART_SERVICE, true));
        keepAliveBox.setChecked(sp.getBoolean(ControlFile.KEY_KEEP_ALIVE, true));
        respectBox.setChecked(sp.getBoolean(ControlFile.KEY_RESPECT_USER_STOP, true));
        a11yBox.setChecked(sp.getBoolean(ControlFile.KEY_RESTORE_A11Y, true));
        watchdogBox.setChecked(sp.getBoolean(ControlFile.KEY_WATCHDOG, true));
    }

    private void saveConfig() {
        remember(ControlFile.KEY_ENABLED, enabledBox.isChecked());
        remember(ControlFile.KEY_RESTART_SERVICE, restartBox.isChecked());
        remember(ControlFile.KEY_KEEP_ALIVE, keepAliveBox.isChecked());
        remember(ControlFile.KEY_RESPECT_USER_STOP, respectBox.isChecked());
        remember(ControlFile.KEY_RESTORE_A11Y, a11yBox.isChecked());
        remember(ControlFile.KEY_WATCHDOG, watchdogBox.isChecked());
        pushConfig();
    }

    private void openTargetApp() {
        try {
            Intent intent = new Intent(ConfigReceiver.ACTION_LAUNCH);
            intent.setClassName(RestartController.TARGET_PKG, RestartController.MAIN_ACTIVITY);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            // let the module know it may restart the service
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    Intent restart = new Intent(ConfigReceiver.ACTION_RESTART);
                    restart.setPackage(RestartController.TARGET_PKG);
                    sendBroadcast(restart);
                }
            }, 1500L);
        } catch (Throwable t) {
            Toast.makeText(this, "无法启动目标应用：" + KLog.describe(t), Toast.LENGTH_LONG).show();
        }
    }

    private void refreshStatus() {
        boolean running = RestartController.isTargetProcessRunning(this);
        String status = ControlFile.readStatus();
        StringBuilder sb = new StringBuilder();
        sb.append("目标应用进程：").append(running ? "运行中" : "未运行（由 Magisk 守护拉起）").append('\n');
        if (status.isEmpty()) {
            sb.append("尚未收到模块状态（把目标应用打开一次即可）。");
        } else {
            sb.append(status);
        }
        statusView.setText(sb.toString());
        if (logView != null && logView.getText().length() == 0) {
            logView.setText(KLog.tail());
        }
    }
}
