package com.codex.aicallguard;

import android.content.Context;
import android.net.Uri;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CallerIdResolver {
    private static final Pattern LOOKUP_ROW = Pattern.compile(
            "tag=(.*?), yellow_page_name=(.*?), atd_category_id=(-?\\d+)");
    private static final Pattern CONTACT_ROW = Pattern.compile(
            "display_name=([^,\\r\\n]+)");
    private static final ExecutorService CONTACT_EXECUTOR = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "contact-lookup");
        thread.setDaemon(true);
        return thread;
    });

    private CallerIdResolver() {
    }

    static void warm() {
        Thread thread = new Thread(() -> RootShell.run(
                "content query --uri content://miui.yellowpage/phone_lookup/10086"
                        + " --projection number",
                4), "yellowpage-warmup");
        thread.setDaemon(true);
        thread.start();
    }

    static CallerLookup lookup(Context context, String rawNumber) {
        String national = nationalNumber(rawNumber);
        if (national.isEmpty()) {
            return CallerLookup.EMPTY;
        }
        CompletableFuture<String> contactFuture = CompletableFuture.supplyAsync(
                () -> ContactResolver.lookup(context, rawNumber),
                CONTACT_EXECUTOR);
        String normalized = normalizedNumber(national);
        StringBuilder command = new StringBuilder();
        appendPhoneLookup(command, national);
        if (national.startsWith("0") && !normalized.equals(national)) {
            appendNormalizedLookup(command, normalized);
        }
        boolean hasContactPermission = ContactResolver.hasPermission(context);
        if (!hasContactPermission
                && RuleEngine.isMobile(national)
                && !RuleEngine.isVirtualNumber(national)) {
            appendContactLookup(command, national);
        }
        CompletableFuture<RootShell.Result> phoneFuture = CompletableFuture.supplyAsync(
                () -> RootShell.run(command.toString(), 4),
                CONTACT_EXECUTOR);
        String contactName = "";
        try {
            contactName = contactFuture.get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        RootShell.Result result;
        try {
            result = phoneFuture.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return CallerLookup.EMPTY;
        }
        if (!result.ok) {
            return CallerLookup.EMPTY;
        }
        if (contactName.isEmpty()) {
            Matcher contactMatcher = CONTACT_ROW.matcher(result.stdout);
            if (contactMatcher.find()) {
                contactName = contactMatcher.group(1).trim();
            }
        }
        CallerInfo info = CallerInfo.EMPTY;
        Matcher phoneMatcher = LOOKUP_ROW.matcher(result.stdout);
        while (phoneMatcher.find()) {
            String tag = phoneMatcher.group(1).trim();
            String name = phoneMatcher.group(2).trim();
            if (!tag.isEmpty() || !name.isEmpty()) {
                info = new CallerInfo(tag, name, parseCid(phoneMatcher.group(3)));
                break;
            }
        }
        return new CallerLookup(info, contactName);
    }

    private static void appendPhoneLookup(StringBuilder command, String candidate) {
        appendCommand(command,
                "content query --uri content://miui.yellowpage/phone_lookup/"
                        + Uri.encode(candidate)
                        + " --projection number:tag:yellow_page_name:atd_category_id");
    }

    private static void appendNormalizedLookup(StringBuilder command, String candidate) {
        appendCommand(command,
                "content query --uri content://miui.yellowpage/"
                        + "phone_lookup_normalized_number/"
                        + Uri.encode(candidate)
                        + " --projection number:tag:yellow_page_name:atd_category_id");
    }

    private static void appendContactLookup(StringBuilder command, String candidate) {
        appendCommand(command,
                "content query --uri content://com.android.contacts/phone_lookup/"
                        + Uri.encode(candidate)
                        + " --projection display_name");
    }

    private static void appendCommand(StringBuilder command, String next) {
        if (command.length() > 0) {
            command.append("; ");
        }
        command.append(next);
    }

    private static int parseCid(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static String nationalNumber(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.replaceAll("[^0-9+]", "");
        if (cleaned.isEmpty()) {
            return "";
        }
        String national = cleaned;
        if (national.startsWith("0086")) {
            national = national.substring(4);
        } else if (national.startsWith("+86")) {
            national = national.substring(3);
        } else if (national.startsWith("86") && national.length() > 11) {
            national = national.substring(2);
        }
        return national;
    }

    private static String normalizedNumber(String national) {
        if (national.startsWith("0")) {
            return "+86" + national.substring(1);
        }
        if (national.length() >= 10) {
            return "+86" + national;
        }
        return national;
    }
}
