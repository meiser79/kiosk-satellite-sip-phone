// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.sip;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.ViewGroup;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;

/** Native full-screen SIP call UI, styled with the host's current theme. */
final class SipCallOverlay implements OverlayFactory {
    interface Actions {
        boolean answer();
        boolean decline();
    }

    final String caller;
    final boolean active;
    private final Actions actions;
    private TextView heading;
    private TextView callerView;
    private Button answerButton;
    private Button endButton;
    private FrameLayout root;
    private int buttonMinWidthPx;

    SipCallOverlay(String caller, boolean active, Actions actions) {
        this.caller = "Unknown caller".equals(caller)
            ? PluginText.get("screen.unknown_caller", "Unknown caller") : caller;
        this.active = active;
        this.actions = actions;
    }

    @Override
    public View create(Context context, KsTheme theme) {
        FrameLayout screen = new FrameLayout(context);
        screen.setBackgroundColor(Color.rgb(16, 17, 20));
        int availableWidth = context.getResources().getDisplayMetrics().widthPixels - theme.px(64);
        int panelWidth = Math.max(theme.px(1), Math.min(availableWidth, theme.px(900)));
        buttonMinWidthPx = Math.round(panelWidth * 0.35f);

        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setPadding(0, theme.px(24), 0, theme.px(24));

        TextView title = new TextView(context);
        title.setGravity(Gravity.CENTER);
        title.setText(PluginText.get(active ? "screen.active" : "screen.incoming",
            active ? "Call in progress" : "Incoming call"));
        theme.styleText(title, 34, 700, "onSurfaceVariant");
        title.setTextColor(Color.rgb(185, 194, 197));

        TextView name = new TextView(context);
        name.setGravity(Gravity.CENTER);
        name.setText(caller);
        name.setPadding(0, theme.px(28), 0, theme.px(44));
        theme.styleText(name, 68, 700, "onSurface");
        name.setTextColor(Color.WHITE);

        LinearLayout actionsRow = new LinearLayout(context);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setGravity(Gravity.CENTER);

        Button accept = new Button(context);
        accept.setText(PluginText.get("button.answer", "Answer"));
        styleButton(accept, theme, Color.rgb(35, 140, 100));
        accept.setOnClickListener(view -> {
            runAction(view, title, true);
        });

        Button end = new Button(context);
        end.setText(PluginText.get(active ? "button.hang_up" : "button.decline",
            active ? "Hang up" : "Decline"));
        styleButton(end, theme, Color.rgb(173, 61, 69));
        end.setOnClickListener(view -> {
            runAction(view, title, false);
        });

        if (!active) actionsRow.addView(accept, buttonLayout(theme));
        actionsRow.addView(end, buttonLayout(theme));
        page.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2));
        page.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2));
        page.addView(actionsRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2));

        screen.addView(page, new FrameLayout.LayoutParams(panelWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root = screen;
        heading = title;
        callerView = name;
        answerButton = accept;
        endButton = end;
        style(theme);
        return screen;
    }

    @Override
    public void onThemeChanged(View view, KsTheme theme) {
        if (view == root) style(theme);
    }

    @Override
    public void onDestroy(View view) {
        if (view == root) {
            root = null;
            heading = null;
            callerView = null;
            answerButton = null;
            endButton = null;
        }
    }

    private void style(KsTheme theme) {
        if (root == null) return;
        root.setBackgroundColor(Color.rgb(16, 17, 20));
        theme.styleText(heading, 34, 700, "onSurfaceVariant");
        heading.setTextColor(Color.rgb(185, 194, 197));
        theme.styleText(callerView, 68, 700, "onSurface");
        callerView.setTextColor(Color.WHITE);
        if (answerButton != null) styleButton(answerButton, theme, Color.rgb(35, 140, 100));
        styleButton(endButton, theme, Color.rgb(173, 61, 69));
    }

    private void styleButton(Button button, KsTheme theme, int color) {
        theme.stylePill(button, true);
        button.setBackgroundTintList(ColorStateList.valueOf(color));
        button.setTextColor(Color.WHITE);
        button.setTextSize(30);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setMinWidth(buttonMinWidthPx);
        button.setMinHeight(theme.px(88));
        button.setPadding(theme.px(40), theme.px(24), theme.px(40), theme.px(24));
    }

    private static LinearLayout.LayoutParams buttonLayout(KsTheme theme) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, theme.px(92));
        params.setMargins(theme.px(12), 0, theme.px(12), 0);
        return params;
    }

    private void runAction(View button, TextView title, boolean answer) {
        button.setEnabled(false);
        Thread worker = new Thread(() -> {
            boolean ok = false;
            try { ok = answer ? actions.answer() : actions.decline(); }
            catch (RuntimeException ignored) { }
            final boolean succeeded = ok;
            title.post(() -> {
                if (!succeeded) {
                    title.setText(PluginText.get("screen.action_failed", "Call action failed"));
                    button.setEnabled(true);
                }
            });
        }, answer ? "sip-answer-action" : "sip-decline-action");
        worker.setDaemon(true);
        worker.start();
    }
}
