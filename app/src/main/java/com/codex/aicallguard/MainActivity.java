package com.codex.aicallguard;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView serviceValue;
    private TextView rootValue;
    private TextView systemValue;
    private TextView testResult;
    private TextView logView;
    private EditText testNumber;
    private EditText blacklistNumber;
    private EditText blacklistNote;
    private LinearLayout blacklistContainer;
    private Switch masterSwitch;
    private boolean resumed;

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            if (!resumed) {
                return;
            }
            refreshDynamicStatus();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestRuntimePermissions();
        setContentView(buildContent());
        ContactResolver.warm(this);
        renderBlacklist();
        refreshStaticControls();
        checkRootAndSystem();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        refreshStaticControls();
        refreshDynamicStatus();
        handler.postDelayed(this::renderBlacklist, 2000);
        if (Prefs.isEnabled(this) && hasPhonePermission() && !GuardService.isRunning()) {
            GuardService.start(this);
        }
        handler.removeCallbacks(refreshTask);
        handler.postDelayed(refreshTask, 2000);
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(refreshTask);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.bg));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        applySystemBarInsets(root);

        TextView title = text(getString(R.string.header_title), 30, R.color.text, true);
        title.setLetterSpacing(0f);
        root.addView(title);
        TextView subtitle = text(getString(R.string.header_subtitle), 14, R.color.muted, false);
        subtitle.setPadding(0, dp(8), 0, dp(20));
        root.addView(subtitle);

        LinearLayout statusPanel = panel();
        statusPanel.addView(sectionTitle(getString(R.string.section_engine)));
        serviceValue = addStatusRow(statusPanel, getString(R.string.status_service));
        rootValue = addStatusRow(statusPanel, getString(R.string.status_root));
        systemValue = addStatusRow(statusPanel, getString(R.string.status_system_auto));
        root.addView(statusPanel);

        LinearLayout masterPanel = panel();
        masterPanel.addView(sectionTitle(getString(R.string.section_master)));
        masterSwitch = addToggle(
                masterPanel,
                getString(R.string.master_title),
                getString(R.string.master_summary),
                Prefs.isEnabled(this),
                checked -> onMasterChanged(checked));
        root.addView(masterPanel);

        LinearLayout conflictPanel = panel();
        conflictPanel.addView(sectionTitle(getString(R.string.section_takeover)));
        addToggle(
                conflictPanel,
                getString(R.string.takeover_title),
                getString(R.string.takeover_summary),
                Prefs.isTakeoverEnabled(this),
                checked -> {
                    Prefs.setTakeoverEnabled(this, checked);
                    GuardService.applyTakeoverSetting(this, checked);
                    checkRootAndSystem();
                });
        addToggle(
                conflictPanel,
                getString(R.string.dry_run_title),
                getString(R.string.dry_run_summary),
                Prefs.isDryRun(this),
                checked -> Prefs.setDryRun(this, checked));
        root.addView(conflictPanel);

        LinearLayout rulesPanel = panel();
        rulesPanel.addView(sectionTitle(getString(R.string.section_rules)));
        RuleConfig rules = Prefs.rules(this);
        addToggle(
                rulesPanel,
                getString(R.string.rule_landline),
                getString(R.string.rule_landline_summary),
                rules.landline,
                checked -> Prefs.setLandline(this, checked));
        addToggle(
                rulesPanel,
                getString(R.string.rule_delivery),
                getString(R.string.rule_delivery_summary),
                rules.delivery,
                checked -> Prefs.setDelivery(this, checked));
        addToggle(
                rulesPanel,
                getString(R.string.rule_sales),
                getString(R.string.rule_sales_summary),
                rules.sales,
                checked -> Prefs.setSales(this, checked));
        addToggle(
                rulesPanel,
                getString(R.string.rule_bank),
                getString(R.string.rule_bank_summary),
                rules.bank,
                checked -> Prefs.setBank(this, checked));
        addToggle(
                rulesPanel,
                getString(R.string.rule_virtual),
                getString(R.string.rule_virtual_summary),
                rules.virtual,
                checked -> Prefs.setVirtual(this, checked));
        addToggle(
                rulesPanel,
                getString(R.string.rule_service),
                getString(R.string.rule_service_summary),
                rules.customerService,
                checked -> Prefs.setCustomerService(this, checked));
        addToggle(
                rulesPanel,
                getString(R.string.rule_unknown_mobile),
                getString(R.string.rule_unknown_mobile_summary),
                rules.unknownMobile,
                checked -> Prefs.setUnknownMobile(this, checked));
        root.addView(rulesPanel);

        LinearLayout blacklistPanel = panel();
        blacklistPanel.addView(sectionTitle(getString(R.string.section_blacklist)));
        TextView blacklistIntro = text(
                getString(R.string.blacklist_intro), 12, R.color.muted, false);
        blacklistIntro.setPadding(0, 0, 0, dp(10));
        blacklistPanel.addView(blacklistIntro);
        blacklistNumber = input(getString(R.string.blacklist_number_hint), InputType.TYPE_CLASS_PHONE);
        blacklistPanel.addView(blacklistNumber);
        blacklistNote = input(getString(R.string.blacklist_note_hint), InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteParams.topMargin = dp(8);
        blacklistPanel.addView(blacklistNote, noteParams);
        Button addBlacklist = button(getString(R.string.blacklist_add));
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addParams.topMargin = dp(10);
        addBlacklist.setLayoutParams(addParams);
        addBlacklist.setOnClickListener(v -> addBlacklistEntry());
        blacklistPanel.addView(addBlacklist);
        blacklistContainer = new LinearLayout(this);
        blacklistContainer.setOrientation(LinearLayout.VERTICAL);
        blacklistContainer.setPadding(0, dp(12), 0, 0);
        blacklistPanel.addView(blacklistContainer);
        root.addView(blacklistPanel);

        LinearLayout testPanel = panel();
        testPanel.addView(sectionTitle(getString(R.string.section_test)));
        LinearLayout testRow = new LinearLayout(this);
        testRow.setOrientation(LinearLayout.HORIZONTAL);
        testRow.setGravity(Gravity.CENTER_VERTICAL);
        testNumber = new EditText(this);
        testNumber.setSingleLine(true);
        testNumber.setHint(R.string.test_hint);
        testNumber.setInputType(InputType.TYPE_CLASS_PHONE);
        testNumber.setTextColor(getColor(R.color.text));
        testNumber.setHintTextColor(getColor(R.color.muted));
        testNumber.setTextSize(15);
        testRow.addView(testNumber, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button check = button(getString(R.string.test_button));
        check.setOnClickListener(v -> runRuleTest());
        testRow.addView(check, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        testPanel.addView(testRow);
        testResult = text("", 13, R.color.muted, false);
        testResult.setPadding(0, dp(12), 0, dp(4));
        testPanel.addView(testResult);

        TextView simulateSummary = text(
                getString(R.string.simulate_summary), 12, R.color.muted, false);
        simulateSummary.setPadding(0, dp(12), 0, dp(8));
        testPanel.addView(simulateSummary);
        Button simulate = secondaryButton(getString(R.string.simulate_button));
        simulate.setOnClickListener(v -> startSimulatedCall());
        testPanel.addView(simulate);
        root.addView(testPanel);

        LinearLayout actions = panel();
        actions.addView(sectionTitle(getString(R.string.section_actions)));
        Button aiSettings = secondaryButton(getString(R.string.open_ai_settings));
        aiSettings.setOnClickListener(v -> openAiSettings());
        actions.addView(aiSettings);
        Button battery = secondaryButton(getString(R.string.open_battery_settings));
        battery.setOnClickListener(v -> openBatterySettings());
        actions.addView(battery);
        root.addView(actions);

        LinearLayout logPanel = panel();
        LinearLayout logHeader = new LinearLayout(this);
        logHeader.setOrientation(LinearLayout.HORIZONTAL);
        logHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView logTitle = sectionTitle(getString(R.string.section_log));
        logHeader.addView(logTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button clear = secondaryButton(getString(R.string.clear_log));
        clear.setOnClickListener(v -> {
            Prefs.clearLog(this);
            refreshLog();
        });
        logHeader.addView(clear);
        logPanel.addView(logHeader);
        logView = text("", 12, R.color.muted, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setLineSpacing(0f, 1.25f);
        logView.setTextIsSelectable(true);
        logPanel.addView(logView);
        root.addView(logPanel);

        return scroll;
    }

    private void applySystemBarInsets(View root) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(dp(20), dp(20) + bars.top, dp(20), dp(28) + bars.bottom);
            return insets;
        });
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.panel_bg);
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        panel.setLayoutParams(params);
        return panel;
    }

    private TextView sectionTitle(String value) {
        TextView view = text(value, 13, R.color.muted, true);
        view.setPadding(0, 0, 0, dp(10));
        return view;
    }

    private TextView addStatusRow(LinearLayout parent, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView left = text(label, 14, R.color.text, false);
        row.addView(left, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = text(getString(R.string.status_checking), 13, R.color.muted, true);
        value.setGravity(Gravity.END);
        row.addView(value);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(6);
        parent.addView(row, params);
        return value;
    }

    private Switch addToggle(
            LinearLayout parent,
            String title,
            String summary,
            boolean checked,
            ToggleListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = text(title, 15, R.color.text, false);
        TextView summaryView = text(summary, 12, R.color.muted, false);
        summaryView.setPadding(0, dp(3), dp(12), 0);
        copy.addView(titleView);
        copy.addView(summaryView);
        row.addView(copy, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        toggle.setChecked(checked);
        toggle.setShowText(false);
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> listener.onChanged(isChecked));
        row.addView(toggle);
        parent.addView(row);
        return toggle;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(getColor(R.color.text));
        button.setBackgroundResource(R.drawable.button_bg);
        button.setPadding(dp(16), dp(8), dp(16), dp(8));
        return button;
    }

    private Button secondaryButton(String label) {
        Button button = button(label);
        button.setTextColor(getColor(R.color.accent));
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        button.setLayoutParams(params);
        return button;
    }

    private EditText input(String hint, int inputType) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setInputType(inputType);
        input.setTextColor(getColor(R.color.text));
        input.setHintTextColor(getColor(R.color.muted));
        input.setTextSize(15);
        input.setPadding(dp(8), dp(8), dp(8), dp(8));
        return input;
    }

    private TextView text(String value, int sp, int colorRes, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(getColor(colorRes));
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        view.setLetterSpacing(0f);
        return view;
    }

    private void onMasterChanged(boolean enabled) {
        if (!enabled) {
            Prefs.setEnabled(this, false);
            GuardService.stop(this);
            refreshDynamicStatus();
            checkRootAndSystem();
            return;
        }
        if (!hasPhonePermission()) {
            masterSwitch.setChecked(false);
            toast(getString(R.string.toast_permission_missing));
            requestRuntimePermissions();
            return;
        }
        worker.execute(() -> {
            boolean root = RootShell.isAvailable();
            runOnUiThread(() -> {
                if (!root) {
                    masterSwitch.setChecked(false);
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.dialog_root_title)
                            .setMessage(R.string.dialog_root_message)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    Prefs.appendLog(this, getString(R.string.log_root_missing));
                    refreshDynamicStatus();
                    return;
                }
                Prefs.setEnabled(this, true);
                GuardService.start(this);
                refreshDynamicStatus();
                checkRootAndSystem();
            });
        });
    }

    private void runRuleTest() {
        String raw = testNumber.getText().toString().trim();
        if (raw.isEmpty()) {
            toast(getString(R.string.toast_test_empty));
            return;
        }
        testResult.setText(R.string.status_checking);
        worker.execute(() -> {
            CallerLookup lookup = CallerIdResolver.lookup(this, raw);
            BlacklistStore.Entry blacklistEntry = BlacklistStore.find(this, raw);
            RuleEngine.Decision decision = RuleEngine.classify(
                    this, raw, lookup, blacklistEntry, Prefs.rules(this));
            String category = RuleEngine.categoryLabel(this, decision.category);
            String verdict = decision.trigger
                    ? getString(R.string.test_trigger)
                    : getString(R.string.test_skip);
            String reason = RuleEngine.reasonLabel(this, decision);
            String caller = lookup.displayName().isEmpty()
                    ? getString(R.string.caller_unknown)
                    : lookup.displayName();
            String result = getString(
                    R.string.test_result,
                    category + " · " + caller,
                    verdict,
                    reason);
            Prefs.appendLog(this, getString(R.string.log_test_header, raw));
            Prefs.appendLog(this, getString(
                    R.string.log_test_decision, category, verdict, reason));
            runOnUiThread(() -> {
                testResult.setText(result);
                refreshLog();
            });
        });
    }

    private void addBlacklistEntry() {
        String rawNumber = blacklistNumber.getText().toString().trim();
        String note = blacklistNote.getText().toString().trim();
        String digits = RuleEngine.digits(rawNumber);
        if (digits.length() < 3) {
            toast(getString(R.string.blacklist_invalid));
            return;
        }
        if (note.length() > 80) {
            note = note.substring(0, 80);
        }
        final String finalNote = note;
        worker.execute(() -> {
            CallerLookup lookup = CallerIdResolver.lookup(this, digits);
            String contactName = lookup.contactName;
            boolean added = BlacklistStore.upsert(
                    this, rawNumber, finalNote, contactName);
            runOnUiThread(() -> {
                blacklistNumber.setText("");
                blacklistNote.setText("");
                renderBlacklist();
                if (!contactName.isEmpty()) {
                    toast(getString(R.string.blacklist_added_contact));
                } else {
                    toast(getString(added
                            ? R.string.blacklist_added
                            : R.string.blacklist_updated));
                }
            });
        });
    }

    private void renderBlacklist() {
        if (blacklistContainer == null) {
            return;
        }
        BlacklistStore.refreshContactNames(this);
        blacklistContainer.removeAllViews();
        java.util.List<BlacklistStore.Entry> entries = BlacklistStore.list(this);
        if (entries.isEmpty()) {
            TextView empty = text(getString(R.string.blacklist_empty), 13, R.color.muted, false);
            empty.setPadding(0, dp(4), 0, dp(4));
            blacklistContainer.addView(empty);
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            BlacklistStore.Entry entry = entries.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));

            LinearLayout copy = new LinearLayout(this);
            copy.setOrientation(LinearLayout.VERTICAL);
            copy.addView(text(entry.number, 15, R.color.text, true));
            String note = entry.note.isEmpty()
                    ? getString(R.string.blacklist_no_note)
                    : entry.note;
            TextView noteView = text(note, 12, R.color.muted, false);
            noteView.setPadding(0, dp(2), dp(12), 0);
            copy.addView(noteView);
            if (!entry.contactName.isEmpty()) {
                TextView contactBadge = text(
                        getString(R.string.blacklist_contact_badge, entry.contactName),
                        11,
                        R.color.warn,
                        false);
                contactBadge.setPadding(0, dp(3), dp(12), 0);
                copy.addView(contactBadge);
            }
            row.addView(copy, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button delete = button(getString(R.string.delete));
            delete.setOnClickListener(v -> {
                BlacklistStore.remove(this, entry.normalized);
                renderBlacklist();
                toast(getString(R.string.blacklist_removed));
            });
            row.addView(delete);
            blacklistContainer.addView(row);

            if (i < entries.size() - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(getColor(R.color.panel_border));
                LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
                blacklistContainer.addView(divider, dividerParams);
            }
        }
    }

    private void startSimulatedCall() {
        String raw = testNumber.getText().toString().trim();
        if (raw.isEmpty()) {
            toast(getString(R.string.toast_test_empty));
            return;
        }
        try {
            TestConnectionService.startTestCall(this, raw, getString(R.string.caller_unknown));
            toast(getString(R.string.simulate_started));
        } catch (Exception e) {
            toast(getString(R.string.simulate_failed, e.getMessage()));
        }
    }

    private void openAiSettings() {
        Intent intent = new Intent();
        intent.setClassName(
                "com.xiaomi.aiasst.service",
                "com.xiaomi.aiasst.service.aicall.settings.main.MiAiSettingsActivity");
        try {
            startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    private void openBatterySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception ignored) {
            try {
                Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                details.setData(Uri.parse("package:" + getPackageName()));
                startActivity(details);
            } catch (Exception ignoredAgain) {
            }
        }
    }

    private void checkRootAndSystem() {
        rootValue.setText(R.string.status_checking);
        worker.execute(() -> {
            boolean root = RootShell.isAvailable();
            String auto = "";
            if (root) {
                RootShell.Result result = RootShell.run(
                        "settings get global com.xiaomi.aiasst.call.autoAnswer", 4);
                if (result.ok) {
                    auto = result.stdout.trim();
                }
            }
            final String autoValue = auto;
            runOnUiThread(() -> {
                rootValue.setText(root ? R.string.status_available : R.string.status_unavailable);
                rootValue.setTextColor(getColor(root ? R.color.ok : R.color.error));
                if (!root) {
                    systemValue.setText(R.string.status_unavailable);
                    systemValue.setTextColor(getColor(R.color.error));
                    return;
                }
                Prefs.appendLog(this, getString(R.string.log_root_ready));
                if (Prefs.isEnabled(this) && GuardService.isRunning()
                        && Prefs.isTakeoverEnabled(this)
                        && Prefs.hasAutoBackup(this)) {
                    systemValue.setText(R.string.status_takeover);
                    systemValue.setTextColor(getColor(R.color.ok));
                } else {
                    systemValue.setText(autoValue.isEmpty() ? "?" : autoValue);
                    systemValue.setTextColor(getColor(R.color.muted));
                }
            });
        });
    }

    private void refreshStaticControls() {
        if (masterSwitch != null) {
            masterSwitch.setChecked(Prefs.isEnabled(this));
        }
        refreshDynamicStatus();
        refreshLog();
    }

    private void refreshDynamicStatus() {
        if (serviceValue == null) {
            return;
        }
        boolean enabled = Prefs.isEnabled(this);
        boolean running = GuardService.isRunning();
        if (!enabled) {
            serviceValue.setText(R.string.status_disabled);
            serviceValue.setTextColor(getColor(R.color.muted));
        } else if (running) {
            serviceValue.setText(R.string.status_enabled);
            serviceValue.setTextColor(getColor(R.color.ok));
        } else {
            serviceValue.setText(R.string.status_checking);
            serviceValue.setTextColor(getColor(R.color.warn));
        }
        refreshLog();
    }

    private void refreshLog() {
        if (logView == null) {
            return;
        }
        String value = Prefs.readLog(this);
        logView.setText(value.isEmpty() ? "—" : value);
    }

    private boolean hasPhonePermission() {
        return checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestRuntimePermissions() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.POST_NOTIFICATIONS
            }, 100);
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.READ_CONTACTS
            }, 100);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private interface ToggleListener {
        void onChanged(boolean checked);
    }
}
