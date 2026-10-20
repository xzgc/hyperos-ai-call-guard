package com.codex.aicallguard;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class BlacklistStore {
    private static final String NAME = "blacklist";
    private static final String KEY_ENTRIES = "entries";

    static final class Entry {
        final String number;
        final String normalized;
        final String note;
        final String contactName;

        Entry(String number, String normalized, String note, String contactName) {
            this.number = number;
            this.normalized = normalized;
            this.note = note == null ? "" : note;
            this.contactName = contactName == null ? "" : contactName;
        }
    }

    private BlacklistStore() {
    }

    static synchronized List<Entry> list(Context context) {
        ArrayList<Entry> result = new ArrayList<>();
        SharedPreferences prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_ENTRIES, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }
                String number = object.optString("number");
                String normalized = object.optString("normalized");
                String note = object.optString("note");
                String contactName = object.optString("contactName");
                if (!normalized.isEmpty()) {
                    result.add(new Entry(number, normalized, note, contactName));
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    static synchronized boolean upsert(
            Context context, String number, String note, String contactName) {
        String normalized = RuleEngine.digits(number);
        if (normalized.isEmpty()) {
            return false;
        }
        List<Entry> entries = list(context);
        boolean added = true;
        ArrayList<Entry> next = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.normalized.equals(normalized)) {
                added = false;
                next.add(new Entry(
                        number.trim(), normalized, note.trim(), contactName.trim()));
            } else {
                next.add(entry);
            }
        }
        if (added) {
            next.add(new Entry(
                    number.trim(), normalized, note.trim(), contactName.trim()));
        }
        save(context, next);
        return added;
    }

    static synchronized void remove(Context context, String normalized) {
        ArrayList<Entry> next = new ArrayList<>();
        for (Entry entry : list(context)) {
            if (!entry.normalized.equals(normalized)) {
                next.add(entry);
            }
        }
        save(context, next);
    }

    static Entry find(Context context, String number) {
        String normalized = RuleEngine.digits(number);
        if (normalized.isEmpty()) {
            return null;
        }
        for (Entry entry : list(context)) {
            if (entry.normalized.equals(normalized)) {
                return entry;
            }
        }
        return null;
    }

    static synchronized void refreshContactNames(Context context) {
        ArrayList<Entry> next = new ArrayList<>();
        boolean changed = false;
        for (Entry entry : list(context)) {
            String contactName = ContactResolver.cachedName(context, entry.number);
            if (!contactName.equals(entry.contactName)) {
                changed = true;
            }
            next.add(new Entry(entry.number, entry.normalized, entry.note, contactName));
        }
        if (changed) {
            save(context, next);
        }
    }

    private static void save(Context context, List<Entry> entries) {
        JSONArray array = new JSONArray();
        for (Entry entry : entries) {
            JSONObject object = new JSONObject();
            try {
                object.put("number", entry.number);
                object.put("normalized", entry.normalized);
                object.put("note", entry.note);
                object.put("contactName", entry.contactName);
                array.put(object);
            } catch (Exception ignored) {
            }
        }
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ENTRIES, array.toString())
                .apply();
    }
}
