package com.codex.aicallguard;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;

import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

final class ContactResolver {
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
    private static final AtomicBoolean LOADING = new AtomicBoolean(false);
    private static final AtomicBoolean RELOAD_PENDING = new AtomicBoolean(false);
    private static volatile boolean loaded;

    private ContactResolver() {
    }

    static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED;
    }

    static void warm(Context context) {
        requestLoad(context, false);
    }

    static void refresh(Context context) {
        requestLoad(context, true);
    }

    private static void requestLoad(Context context, boolean force) {
        if (!hasPermission(context) || (!force && loaded)) {
            return;
        }
        if (!LOADING.compareAndSet(false, true)) {
            if (force) {
                RELOAD_PENDING.set(true);
            }
            return;
        }
        Context appContext = context.getApplicationContext();
        Thread thread = new Thread(() -> load(appContext), "contact-cache");
        thread.setDaemon(true);
        thread.start();
    }

    static String lookup(Context context, String rawNumber) {
        if (!hasPermission(context)) {
            return "";
        }
        String digits = RuleEngine.digits(rawNumber);
        if (digits.isEmpty()) {
            return "";
        }
        if (loaded) {
            String name = CACHE.get(digits);
            return name == null ? "" : name;
        }
        warm(context);
        Uri uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(digits));
        try (Cursor cursor = context.getContentResolver().query(
                uri,
                new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME},
                null,
                null,
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                return name == null ? "" : name.trim();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    static String cachedName(Context context, String rawNumber) {
        if (!hasPermission(context) || !loaded) {
            return "";
        }
        String digits = RuleEngine.digits(rawNumber);
        String name = CACHE.get(digits);
        return name == null ? "" : name;
    }

    private static void load(Context context) {
        Map<String, String> next = new HashMap<>();
        boolean success = false;
        try (Cursor cursor = context.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                },
                null,
                null,
                null)) {
            if (cursor != null) {
                int numberIndex = cursor.getColumnIndex(
                        ContactsContract.CommonDataKinds.Phone.NUMBER);
                int nameIndex = cursor.getColumnIndex(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                while (cursor.moveToNext()) {
                    String number = cursor.getString(numberIndex);
                    String name = cursor.getString(nameIndex);
                    String digits = RuleEngine.digits(number);
                    if (!digits.isEmpty() && name != null) {
                        next.putIfAbsent(digits, name.trim());
                    }
                }
            }
            success = true;
        } catch (Exception ignored) {
        } finally {
            if (success) {
                CACHE.clear();
                CACHE.putAll(next);
                loaded = true;
            }
            LOADING.set(false);
            if (RELOAD_PENDING.getAndSet(false)) {
                refresh(context);
            }
        }
    }
}
