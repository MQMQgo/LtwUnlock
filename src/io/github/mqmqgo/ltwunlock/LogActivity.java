package io.github.mqmqgo.ltwunlock;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Launcher UI: view / clear / export LtwUnlock hook + app logs.
 */
public final class LogActivity extends Activity {
    private static final String SCOPE_HINT =
            "Scope: 系统框架(android/system) + com.microsoft.appmanager\n"
                    + "Enable in LSPosed → Modules → LTW Unlock → 勾选后重启";

    private TextView logView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int side = Ui.dp(this, 16);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(side, Ui.dp(this, 20), side, side);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = Ui.text(this, "‹", 30, R.color.ltw_on_bg, false);
        back.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 16), Ui.dp(this, 4));
        back.setBackground(Ui.rowRipple(this));
        back.setOnClickListener(v -> finish());
        top.addView(back);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(Ui.text(this, getString(R.string.log_title), 24, R.color.ltw_on_bg, false));
        titles.addView(Ui.text(this, buildHeader(), 12, R.color.ltw_on_bg_variant, false));
        top.addView(titles);
        root.addView(top);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(-1, -2);
        btnRowLp.topMargin = Ui.dp(this, 16);
        btnRowLp.bottomMargin = Ui.dp(this, 12);
        root.addView(buttons, btnRowLp);
        buttons.addView(Ui.button(this, "刷新", false, v -> refresh()), weightLp(this));
        buttons.addView(Ui.button(this, "清空", false, v -> {
            ModuleLog.clear(this);
            refresh();
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
        }), weightLp(this));
        buttons.addView(Ui.button(this, "导出", true, v -> exportLog()), weightLp(this));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(Ui.round(getColor(R.color.ltw_log_bg), Ui.dp(this, 20)));
        scroll.setClipToOutline(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        HorizontalScrollView hScroll = new HorizontalScrollView(this);
        scroll.addView(hScroll, new ScrollView.LayoutParams(-1, -2));

        logView = new TextView(this);
        logView.setTextColor(getColor(R.color.ltw_log_text));
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        int p = Ui.dp(this, 14);
        logView.setPadding(p, p, p, p);
        hScroll.addView(logView);

        setContentView(root);
        ModuleLog.ui(this, "LogActivity opened");
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private String buildHeader() {
        String ver = "?.?.?";
        int code = -1;
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            ver = pi.versionName != null ? pi.versionName : ver;
            code = (int) pi.getLongVersionCode();
        } catch (Throwable ignored) {
        }
        return "v" + ver + " (" + code + ") · 系统框架 + 连接到 Windows";
    }

    private void refresh() {
        final String text = ModuleLog.readCombined(this);
        logView.setText(text);
        logView.post(() -> {
            View parent = (View) logView.getParent();
            if (parent != null) {
                parent = (View) parent.getParent();
            }
            if (parent instanceof ScrollView) {
                ((ScrollView) parent).fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void exportLog() {
        String text = ModuleLog.readCombined(this);
        try {
            File out = new File(getCacheDir(), "ltwunlock-export.txt");
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(out, false), StandardCharsets.UTF_8)) {
                w.write(text);
            }
            Uri uri = LtwFileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", out);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "LtwUnlock log");
            String body = text.length() > 200_000
                    ? text.substring(text.length() - 200_000) : text;
            send.putExtra(Intent.EXTRA_TEXT, body);
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "导出 LtwUnlock 日志"));
            ModuleLog.ui(this, "Export shared (" + out.length() + " bytes)");
        } catch (Throwable t) {
            try {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_SUBJECT, "LtwUnlock log");
                send.putExtra(Intent.EXTRA_TEXT, text.length() > 200_000
                        ? text.substring(text.length() - 200_000) : text);
                startActivity(Intent.createChooser(send, "导出 LtwUnlock 日志"));
            } catch (Throwable t2) {
                Toast.makeText(this, "导出失败：" + t2.getMessage(),
                        Toast.LENGTH_LONG).show();
                ModuleLog.e("Export failed", t2);
            }
        }
    }

    private static LinearLayout.LayoutParams weightLp(android.content.Context ctx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        int m = Ui.dp(ctx, 4);
        lp.setMargins(m, 0, m, 0);
        return lp;
    }
}
