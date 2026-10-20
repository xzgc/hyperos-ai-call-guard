package com.codex.aicallguard;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class Prefs {
    private static final String NAME = "guard";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_LANDLINE = "rule_landline";
    private static final String KEY_DELIVERY = "rule_delivery";
    private static final String KEY_SALES = "rule_sales";
    private static final String KEY_BANK = "rule_bank";
    private static final String KEY_VIRTUAL = "rule_virtual";
    private static final String KEY_CUSTOMER_SERVICE = "rule_customer_service";
    private static final String KEY_UNKNOWN_MOBILE = "rule_unknown_mobile";
    private static final String KEY_TAKEOVER = "takeover_system";
    private static final String KEY_DRY_RUN = "dry_run";
    private static final String KEY_LOG = "event_log";
    private static final String KEY_AUTO_BACKED_UP = "auto_value_backed_up";
    private static final String KEY_AUTO_PREVIOUS = "auto_value_previous";

    private Prefs() {
    }

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context context) {
        return get(context).getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        get(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    static RuleConfig rules(Context context) {
        SharedPreferences p = get(context);
        return new RuleConfig(
                p.getBoolean(KEY_LANDLINE, true),
                p.getBoolean(KEY_DELIVERY, true),
                p.getBoolean(KEY_SALES, true),
                p.getBoolean(KEY_BANK, true),
                p.getBoolean(KEY_VIRTUAL, true),
                p.getBoolean(KEY_CUSTOMER_SERVICE, true),
                p.getBoolean(KEY_UNKNOWN_MOBILE, true)
        );
    }

    static void setRule(Context context, String key, boolean value) {
        get(context).edit().putBoolean(key, value).apply();
    }

    static void setLandline(Context context, boolean value) {
        setRule(context, KEY_LANDLINE, value);
    }

    static void setDelivery(Context context, boolean value) {
        setRule(context, KEY_DELIVERY, value);
    }

    static void setSales(Context context, boolean value) {
        setRule(context, KEY_SALES, value);
    }

    static void setBank(Context context, boolean value) {
        setRule(context, KEY_BANK, value);
    }

    static void setVirtual(Context context, boolean value) {
        setRule(context, KEY_VIRTUAL, value);
    }

    static void setCustomerService(Context context, boolean value) {
        setRule(context, KEY_CUSTOMER_SERVICE, value);
    }

    static void setUnknownMobile(Context context, boolean value) {
        setRule(context, KEY_UNKNOWN_MOBILE, value);
    }

    static boolean isTakeoverEnabled(Context context) {
        return get(context).getBoolean(KEY_TAKEOVER, true);
    }

    static void setTakeoverEnabled(Context context, boolean value) {
        get(context).edit().putBoolean(KEY_TAKEOVER, value).apply();
    }

    static boolean isDryRun(Context context) {
        return get(context).getBoolean(KEY_DRY_RUN, false);
    }

    static void setDryRun(Context context, boolean value) {
        get(context).edit().putBoolean(KEY_DRY_RUN, value).apply();
    }

    static boolean hasAutoBackup(Context context) {
        return get(context).getBoolean(KEY_AUTO_BACKED_UP, false);
    }

    static void saveAutoBackup(Context context, String value) {
        get(context).edit()
                .putBoolean(KEY_AUTO_BACKED_UP, true)
                .putString(KEY_AUTO_PREVIOUS, value)
                .apply();
    }

    static String getAutoBackup(Context context) {
        return get(context).getString(KEY_AUTO_PREVIOUS, "1");
    }

    static void clearAutoBackup(Context context) {
        get(context).edit()
                .remove(KEY_AUTO_BACKED_UP)
                .remove(KEY_AUTO_PREVIOUS)
                .apply();
    }

    static synchronized void appendLog(Context context, String line) {
        SharedPreferences p = get(context);
        String prefix = new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date());
        String entry = prefix + "  " + line;
        String old = p.getString(KEY_LOG, "");
        String[] lines = old.isEmpty() ? new String[0] : old.split("\n");
        StringBuilder next = new StringBuilder(entry);
        int keep = Math.min(lines.length, 39);
        for (int i = 0; i < keep; i++) {
            next.append('\n').append(lines[i]);
        }
        p.edit().putString(KEY_LOG, next.toString()).apply();
    }

    static String readLog(Context context) {
        return get(context).getString(KEY_LOG, "");
    }

    static void clearLog(Context context) {
        get(context).edit().remove(KEY_LOG).apply();
    }

    static String maskNumber(String number) {
        if (number == null || number.length() <= 7) {
            return number == null ? "" : number;
        }
        return number.substring(0, 3) + "****" + number.substring(number.length() - 4);
    }
}
