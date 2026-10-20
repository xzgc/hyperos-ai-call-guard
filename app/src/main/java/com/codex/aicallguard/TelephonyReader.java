package com.codex.aicallguard;

final class TelephonyReader {
    static final class Snapshot {
        final int phoneId;
        final String number;

        Snapshot(int phoneId, String number) {
            this.phoneId = phoneId;
            this.number = number;
        }
    }

    private TelephonyReader() {
    }

    static Snapshot readIncomingNumber() {
        for (int attempt = 0; attempt < 5; attempt++) {
            RootShell.Result result = RootShell.run("dumpsys telephony.registry", 5);
            if (result.ok) {
                Snapshot parsed = parse(result.stdout);
                if (parsed != null && !parsed.number.isEmpty()) {
                    return parsed;
                }
            }
            try {
                Thread.sleep(attempt == 0 ? 120 : 240);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    static Snapshot parse(String dump) {
        int phoneId = -1;
        int state = -1;
        String number = "";
        Snapshot best = null;
        for (String rawLine : dump.split("\n")) {
            String line = rawLine.trim();
            if (line.startsWith("Phone Id=")) {
                Snapshot candidate = makeSnapshot(phoneId, state, number);
                if (candidate != null) {
                    best = candidate;
                }
                phoneId = parseInt(line.substring("Phone Id=".length()), -1);
                state = -1;
                number = "";
            } else if (line.startsWith("mCallState=")) {
                state = parseInt(line.substring("mCallState=".length()), -1);
            } else if (line.startsWith("mCallIncomingNumber=")) {
                number = line.substring("mCallIncomingNumber=".length()).trim();
            }
        }
        Snapshot candidate = makeSnapshot(phoneId, state, number);
        if (candidate != null) {
            best = candidate;
        }
        return best;
    }

    private static Snapshot makeSnapshot(int phoneId, int state, String number) {
        if (phoneId < 0 || state != 1 || number == null || number.isEmpty()) {
            return null;
        }
        return new Snapshot(phoneId, number);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
