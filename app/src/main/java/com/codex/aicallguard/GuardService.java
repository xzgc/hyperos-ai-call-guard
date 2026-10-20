package com.codex.aicallguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.provider.ContactsContract;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class GuardService extends Service {
    static final String ACTION_START = "com.codex.aicallguard.START";
    static final String ACTION_STOP = "com.codex.aicallguard.STOP";
    static final String ACTION_TEST_RING = "com.codex.aicallguard.TEST_RING";
    static final String EXTRA_TEST_NUMBER = "test_number";
    static final String EXTRA_TEST_NAME = "test_name";

    private static volatile boolean running;
    private static final long CONTACT_REFRESH_INTERVAL_MS = 10 * 60 * 1000L;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable contactRefreshTask = new Runnable() {
        @Override
        public void run() {
            ContactResolver.refresh(GuardService.this);
            mainHandler.postDelayed(
                    () -> BlacklistStore.refreshContactNames(GuardService.this), 2000);
            mainHandler.postDelayed(this, CONTACT_REFRESH_INTERVAL_MS);
        }
    };
    private TelephonyManager telephonyManager;
    private TelephonyCallback telephonyCallback;
    private BroadcastReceiver phoneStateReceiver;
    private ContentObserver contactObserver;
    private String lastHandledNumber = "";
    private long lastHandledAt;
    private String lastStatus = "";

    static boolean isRunning() {
        return running;
    }

    static void start(Context context) {
        Intent intent = new Intent(context, GuardService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    static void stop(Context context) {
        Intent intent = new Intent(context, GuardService.class).setAction(ACTION_STOP);
        context.startService(intent);
    }

    static void testRing(Context context, String number, String name) {
        Intent intent = new Intent(context, GuardService.class)
                .setAction(ACTION_TEST_RING)
                .putExtra(EXTRA_TEST_NUMBER, number)
                .putExtra(EXTRA_TEST_NAME, name);
        context.startService(intent);
    }

    static void applyTakeoverSetting(Context context, boolean enabled) {
        Thread thread = new Thread(() -> {
            if (enabled) {
                takeOverSystemAutoAnswer(context);
            } else if (Prefs.hasAutoBackup(context)) {
                restoreSystemAutoAnswer(context, Prefs.getAutoBackup(context));
            }
        }, "takeover-setting");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startAsForeground(getString(R.string.notification_idle));
        running = true;
        ContactResolver.warm(this);
        CallerIdResolver.warm();
        registerContactObserver();
        mainHandler.postDelayed(contactRefreshTask, CONTACT_REFRESH_INTERVAL_MS);
        registerTelephonyListener();
        registerPhoneStateReceiver();
        if (Prefs.isTakeoverEnabled(this)) {
            worker.execute(() -> takeOverSystemAutoAnswer(this));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Prefs.setEnabled(this, false);
            if (Prefs.hasAutoBackup(this)) {
                String previous = Prefs.getAutoBackup(this);
                worker.execute(() -> {
                    restoreSystemAutoAnswer(this, previous);
                    mainHandler.post(this::stopSelf);
                });
            } else {
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (!Prefs.isEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_TEST_RING.equals(intent.getAction())) {
            String testNumber = intent.getStringExtra(EXTRA_TEST_NUMBER);
            String testName = intent.getStringExtra(EXTRA_TEST_NAME);
            worker.execute(() -> processIncoming(testNumber, testName, true));
        }
        startAsForeground(getString(R.string.notification_idle));
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        unregisterTelephonyListener();
        unregisterPhoneStateReceiver();
        unregisterContactObserver();
        mainHandler.removeCallbacks(contactRefreshTask);
        if (Prefs.isTakeoverEnabled(this) && Prefs.hasAutoBackup(this)) {
            final String previous = Prefs.getAutoBackup(this);
            new Thread(() -> restoreSystemAutoAnswer(this, previous), "restore-auto-answer").start();
        }
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void registerTelephonyListener() {
        try {
            telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            TelephonyCallback.CallStateListener listener = state -> {
                if (state == TelephonyManager.CALL_STATE_RINGING) {
                    scheduleRingRead(null);
                }
            };
            telephonyCallback = new CallStateCallback(listener);
            telephonyManager.registerTelephonyCallback(getMainExecutor(), telephonyCallback);
            Prefs.appendLog(this, getString(R.string.log_listener_started));
        } catch (Exception e) {
            Prefs.appendLog(this, getString(R.string.log_listener_failed, e.getMessage()));
        }
    }

    private void unregisterTelephonyListener() {
        try {
            if (telephonyManager != null && telephonyCallback != null) {
                telephonyManager.unregisterTelephonyCallback(telephonyCallback);
            }
        } catch (Exception ignored) {
        }
    }

    private void registerPhoneStateReceiver() {
        phoneStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) {
                    return;
                }
                String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
                if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
                    String number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
                    scheduleRingRead(number);
                }
            }
        };
        IntentFilter filter = new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(phoneStateReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(phoneStateReceiver, filter);
        }
    }

    private void registerContactObserver() {
        if (!ContactResolver.hasPermission(this)) {
            return;
        }
        contactObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                ContactResolver.refresh(GuardService.this);
                mainHandler.postDelayed(
                        () -> BlacklistStore.refreshContactNames(GuardService.this), 2000);
            }
        };
        try {
            getContentResolver().registerContentObserver(
                    ContactsContract.Contacts.CONTENT_URI, true, contactObserver);
            getContentResolver().registerContentObserver(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI, true, contactObserver);
        } catch (Exception ignored) {
        }
    }

    private void unregisterContactObserver() {
        try {
            if (contactObserver != null) {
                getContentResolver().unregisterContentObserver(contactObserver);
                contactObserver = null;
            }
        } catch (Exception ignored) {
        }
    }

    private void unregisterPhoneStateReceiver() {
        try {
            if (phoneStateReceiver != null) {
                unregisterReceiver(phoneStateReceiver);
            }
        } catch (Exception ignored) {
        }
    }

    private void scheduleRingRead(String hintNumber) {
        if (!processing.compareAndSet(false, true)) {
            return;
        }
        worker.execute(() -> {
            String number = RuleEngine.digits(hintNumber);
            String name = "";
            String rawNumber = hintNumber;
            if (number.isEmpty()) {
                TelephonyReader.Snapshot snapshot = TelephonyReader.readIncomingNumber();
                if (snapshot != null) {
                    rawNumber = snapshot.number;
                }
            }
            processIncoming(rawNumber, name, false);
            mainHandler.postDelayed(() -> processing.set(false), 4000);
        });
    }

    private void processIncoming(String rawNumber, String testName, boolean test) {
        try {
            String digits = RuleEngine.digits(rawNumber);
            if (digits.isEmpty()) {
                Prefs.appendLog(this, getString(R.string.log_number_missing));
                setStatus(getString(R.string.status_number_missing));
                return;
            }
            long now = System.currentTimeMillis();
            if (digits.equals(lastHandledNumber) && now - lastHandledAt < 8000) {
                return;
            }
            lastHandledNumber = digits;
            lastHandledAt = now;

            CallerLookup callerLookup;
            if (testName == null || testName.isEmpty()) {
                callerLookup = CallerIdResolver.lookup(this, rawNumber);
            } else {
                callerLookup = new CallerLookup(new CallerInfo("", testName, -1), "");
            }
            BlacklistStore.Entry blacklistEntry = BlacklistStore.find(this, digits);
            RuleEngine.Decision decision = RuleEngine.classify(
                    this, rawNumber, callerLookup, blacklistEntry, Prefs.rules(this));
            String masked = Prefs.maskNumber(digits);
            String caller = callerLookup.displayName().isEmpty()
                    ? getString(R.string.caller_unknown)
                    : callerLookup.displayName();

            if (!decision.trigger) {
                Prefs.appendLog(this, getString(
                        R.string.log_skipped,
                        masked,
                        caller,
                        RuleEngine.reasonLabel(this, decision)));
                setStatus(getString(R.string.status_listening));
                return;
            }

            String category = RuleEngine.categoryLabel(this, decision.category);
            Prefs.appendLog(this, getString(
                    R.string.log_matched,
                    masked,
                    caller,
                    category));

            if (test || Prefs.isDryRun(this)) {
                Prefs.appendLog(this, getString(R.string.log_debug_no_broadcast));
                setStatus(getString(R.string.status_test_matched, category));
                return;
            }

            RootShell.Result result = sendAiCall(digits);
            if (result.ok) {
                Prefs.appendLog(this, getString(R.string.log_broadcast_ok, category));
                setStatus(getString(R.string.status_taken_over, category));
            } else {
                String message = result.combined();
                if (message.length() > 120) {
                    message = message.substring(0, 120);
                }
                Prefs.appendLog(this, getString(R.string.log_broadcast_failed, message));
                setStatus(getString(R.string.status_root_failed));
            }
        } finally {
            if (test) {
                processing.set(false);
            }
        }
    }

    private RootShell.Result sendAiCall(String digits) {
        String safeNumber = digits.replaceAll("[^0-9+]", "");
        String command = "am broadcast"
                + " -a com.xiaomi.aiasst.service.use_ai_call"
                + " --es incoming_number " + safeNumber
                + " --ei ai_call_mode 2"
                + " -n com.xiaomi.aiasst.service/.aicall.receiver.UseAiCallReceiver";
        return RootShell.run(command, 6);
    }

    private static void takeOverSystemAutoAnswer(Context context) {
        if (Prefs.hasAutoBackup(context)) {
            RootShell.run("settings put global com.xiaomi.aiasst.call.autoAnswer 0", 4);
            return;
        }
        RootShell.Result previous = RootShell.run(
                "settings get global com.xiaomi.aiasst.call.autoAnswer", 4);
        if (!previous.ok) {
            Prefs.appendLog(context, context.getString(R.string.log_takeover_failed));
            return;
        }
        String value = previous.stdout.trim();
        if (value.isEmpty() || "null".equals(value)) {
            value = "1";
        }
        Prefs.saveAutoBackup(context, value);
        RootShell.Result changed = RootShell.run(
                "settings put global com.xiaomi.aiasst.call.autoAnswer 0", 4);
        Prefs.appendLog(context, changed.ok
                ? context.getString(R.string.log_takeover_ok, value)
                : context.getString(R.string.log_takeover_failed));
    }

    private static void restoreSystemAutoAnswer(Context context, String previous) {
        String value = previous == null || previous.isEmpty() ? "1" : previous;
        RootShell.Result result = RootShell.run(
                "settings put global com.xiaomi.aiasst.call.autoAnswer " + value, 4);
        if (result.ok) {
            Prefs.clearAutoBackup(context);
            Prefs.appendLog(context, context.getString(R.string.log_restore_ok, value));
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                "guard",
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private void startAsForeground(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, "guard")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(text)
                .setContentIntent(pending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(10, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(10, notification);
        }
    }

    private void setStatus(String text) {
        lastStatus = text;
        mainHandler.post(() -> startAsForeground(text));
    }

    private static final class CallStateCallback extends TelephonyCallback
            implements TelephonyCallback.CallStateListener {
        private final TelephonyCallback.CallStateListener delegate;

        CallStateCallback(TelephonyCallback.CallStateListener delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onCallStateChanged(int state) {
            delegate.onCallStateChanged(state);
        }
    }
}
