package io.github.mqmqgo.ltwunlock;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
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

        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (12 * dp);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.parseColor("#121212"));

        TextView headerView = new TextView(this);
        headerView.setTextColor(Color.parseColor("#E0E0E0"));
        headerView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        headerView.setTypeface(Typeface.SANS_SERIF);
        headerView.setText(buildHeader());
        root.addView(headerView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        btnRowLp.topMargin = (int) (8 * dp);
        btnRowLp.bottomMargin = (int) (8 * dp);
        root.addView(buttons, btnRowLp);

        buttons.addView(makeButton("Refresh", v -> refresh()), weightLp(1f, dp));
        buttons.addView(makeButton("Clear", v -> {
            ModuleLog.clear(this);
            refresh();
            Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show();
        }), weightLp(1f, dp));
        buttons.addView(makeButton("Export", v -> exportLog()), weightLp(1f, dp));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, scrollLp);

        HorizontalScrollView hScroll = new HorizontalScrollView(this);
        scroll.addView(hScroll, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        logView = new TextView(this);
        logView.setTextColor(Color.parseColor("#A5D6A7"));
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setPadding((int) (4 * dp), (int) (4 * dp), (int) (4 * dp), (int) (4 * dp));
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
        return "LTW Unlock  v" + ver + " (" + code + ")\n" + SCOPE_HINT;
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
            startActivity(Intent.createChooser(send, "Export LtwUnlock log"));
            ModuleLog.ui(this, "Export shared (" + out.length() + " bytes)");
        } catch (Throwable t) {
            try {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_SUBJECT, "LtwUnlock log");
                send.putExtra(Intent.EXTRA_TEXT, text.length() > 200_000
                        ? text.substring(text.length() - 200_000) : text);
                startActivity(Intent.createChooser(send, "Export LtwUnlock log"));
            } catch (Throwable t2) {
                Toast.makeText(this, "Export failed: " + t2.getMessage(),
                        Toast.LENGTH_LONG).show();
                ModuleLog.e("Export failed", t2);
            }
        }
    }

    private Button makeButton(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        return b;
    }

    private static LinearLayout.LayoutParams weightLp(float weight, float dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        int m = (int) (4 * dp);
        lp.setMargins(m, 0, m, 0);
        return lp;
    }
}
