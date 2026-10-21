package io.github.mqmqgo.ltwunlock;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Module home / settings (launcher alias + LSPosed MODULE_SETTINGS entry). */
public final class MainActivity extends Activity {
    private TextView statusText;
    private TextView statusPill;
    private Switch hideSwitch;
    private boolean binding;

    private ComponentName alias() {
        return new ComponentName(this, getPackageName() + ".LauncherAlias");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int side = Ui.dp(this, 16);
        root.setPadding(side, Ui.dp(this, 28), side, Ui.dp(this, 24));
        scroll.addView(root);

        // Header: icon + title + version
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 8));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_launcher);
        header.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(Ui.dp(this, 16), 0, 0, 0);
        titles.addView(Ui.text(this, getString(R.string.app_name), 26, R.color.ltw_on_bg, false));
        titles.addView(Ui.text(this, "版本 " + versionString(), 13, R.color.ltw_on_bg_variant, false));
        header.addView(titles);
        root.addView(header);

        // Status card
        LinearLayout status = Ui.card(this);
        status.setBackground(Ui.round(getColor(R.color.ltw_primary_container), Ui.dp(this, 24)));
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView st = Ui.text(this, "模块状态", 16, R.color.ltw_on_primary_container, true);
        statusRow.addView(st, new LinearLayout.LayoutParams(0, -2, 1f));
        statusPill = (TextView) Ui.pill(this, "检测中…", R.color.ltw_card, R.color.ltw_on_bg_variant);
        statusRow.addView(statusPill);
        status.addView(statusRow);
        statusText = Ui.text(this, "", 13, R.color.ltw_on_primary_container, false);
        statusText.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 12));
        status.addView(statusText);
        status.addView(Ui.text(this, getString(R.string.desc), 15, R.color.ltw_on_primary_container, false));
        root.addView(status, Ui.cardLp(this));

        // Scope card
        LinearLayout scope = Ui.card(this);
        scope.addView(Ui.text(this, "作用域", 16, R.color.ltw_on_bg, true));
        TextView sc = Ui.text(this, "• 系统框架（android / system）\n• 连接到 Windows（com.microsoft.appmanager）\n• 本模块（用于检测是否已激活）\n在 LSPosed 中勾选以上作用域后重启设备生效。",
                14, R.color.ltw_on_bg_variant, false);
        sc.setPadding(0, Ui.dp(this, 8), 0, 0);
        scope.addView(sc);
        root.addView(scope, Ui.cardLp(this));

        // Settings card: logs + hide icon
        LinearLayout settings = Ui.card(this);
        settings.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        settings.addView(row("运行日志", "查看、清空或导出 Hook 日志", null,
                v -> startActivity(new Intent(this, LogActivity.class))));
        View div = new View(this);
        div.setBackgroundColor(getColor(R.color.ltw_card_stroke));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 1));
        dlp.setMargins(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4));
        settings.addView(div, dlp);
        hideSwitch = new Switch(this);
        int[][] states = {{android.R.attr.state_checked}, {}};
        hideSwitch.setThumbTintList(new ColorStateList(states, new int[]{
                getColor(R.color.ltw_primary), getColor(R.color.ltw_on_bg_variant)}));
        hideSwitch.setTrackTintList(new ColorStateList(states, new int[]{
                getColor(R.color.ltw_primary_container), getColor(R.color.ltw_card_stroke)}));
        hideSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (!binding) setIconHidden(checked);
        });
        settings.addView(row("隐藏桌面图标", "隐藏后仍可在 LSPosed 管理器的模块列表中点击本模块打开此页面",
                hideSwitch, v -> hideSwitch.toggle()));
        root.addView(settings, Ui.cardLp(this));

        TextView foot = Ui.text(this, "MQMQgo · github.com/MQMQgo/LtwUnlock", 12, R.color.ltw_on_bg_variant, false);
        foot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams flp = Ui.cardLp(this);
        flp.topMargin = Ui.dp(this, 24);
        root.addView(foot, flp);

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        binding = true;
        hideSwitch.setChecked(isIconHidden());
        binding = false;
        refreshStatus();
    }

    private View row(String title, String sub, View trailing, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        r.setBackground(Ui.rowRipple(this));
        r.setClickable(true);
        r.setOnClickListener(click);
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(Ui.text(this, title, 16, R.color.ltw_on_bg, true));
        TextView s = Ui.text(this, sub, 13, R.color.ltw_on_bg_variant, false);
        s.setPadding(0, Ui.dp(this, 2), 0, 0);
        t.addView(s);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        if (trailing != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = Ui.dp(this, 12);
            r.addView(trailing, lp);
        } else {
            TextView chev = Ui.text(this, "›", 24, R.color.ltw_on_bg_variant, false);
            r.addView(chev);
        }
        return r;
    }

    private boolean isIconHidden() {
        int s = getPackageManager().getComponentEnabledSetting(alias());
        // DEFAULT => manifest enabled="true"
        return s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    private void setIconHidden(boolean hidden) {
        try {
            getPackageManager().setComponentEnabledSetting(alias(),
                    hidden ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            ModuleLog.ui(this, "Launcher icon " + (hidden ? "hidden" : "shown"));
        } catch (Throwable t) {
            ModuleLog.e("setComponentEnabledSetting failed", t);
        }
        binding = true;
        hideSwitch.setChecked(isIconHidden());
        binding = false;
    }

    private void refreshStatus() {
        boolean on = ModuleStatus.isActivated();
        if (on) {
            statusPill.setText("已激活");
            statusPill.setBackground(Ui.round(getColor(R.color.ltw_primary), Ui.dp(this, 100)));
            statusPill.setTextColor(getColor(R.color.ltw_on_primary));
            statusText.setText("模块已在 LSPosed 中生效");
        } else {
            statusPill.setText("未激活");
            statusPill.setBackground(Ui.round(getColor(R.color.ltw_accent_container), Ui.dp(this, 100)));
            statusPill.setTextColor(getColor(R.color.ltw_accent));
            statusText.setText("请在 LSPosed 中启用本模块，勾选推荐作用域（系统框架 + 连接到 Windows + 本模块），然后重启设备；若仍显示未激活，可强制停止本应用后再打开。");
        }
    }

    private String versionString() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName + " (" + pi.getLongVersionCode() + ")";
        } catch (Throwable t) {
            return "?";
        }
    }
}
