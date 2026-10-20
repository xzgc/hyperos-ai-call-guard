package com.codex.aicallguard;

import android.net.Uri;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;

public class TestConnectionService extends ConnectionService {
    private Connection activeConnection;
    private PhoneAccountHandle accountHandle;
    private String testNumber = "95555";
    private String testName = "招商银行";

    @Override
    public Connection onCreateIncomingConnection(
            PhoneAccountHandle connectionManagerPhoneAccount,
            ConnectionRequest request) {
        TestConnection connection = new TestConnection();
        connection.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED);
        connection.setAddress(Uri.parse("tel:" + testNumber), TelecomManager.PRESENTATION_ALLOWED);
        connection.setCallerDisplayName(
                getString(R.string.simulate_title), TelecomManager.PRESENTATION_ALLOWED);
        connection.setAudioModeIsVoip(false);
        connection.setInitializing();
        connection.setInitialized();
        connection.setRingbackRequested(false);
        connection.markRinging();
        activeConnection = connection;
        return connection;
    }

    @Override
    public void onCreateIncomingConnectionFailed(
            PhoneAccountHandle connectionManagerPhoneAccount,
            ConnectionRequest request) {
        Prefs.appendLog(this, getString(R.string.log_number_missing));
    }

    public static void startTestCall(MainActivity activity, String number, String name) {
        TelecomManager telecom = (TelecomManager) activity.getSystemService(TELECOM_SERVICE);
        if (telecom == null) {
            throw new IllegalStateException("TelecomManager unavailable");
        }
        android.content.ComponentName component =
                new android.content.ComponentName(activity, TestConnectionService.class);
        PhoneAccountHandle handle = new PhoneAccountHandle(component, "ai_call_guard_test");
        android.telecom.PhoneAccount account = android.telecom.PhoneAccount.builder(
                        handle, activity.getString(R.string.simulate_title))
                .setCapabilities(android.telecom.PhoneAccount.CAPABILITY_SELF_MANAGED)
                .build();
        telecom.registerPhoneAccount(account);
        android.os.Bundle extras = new android.os.Bundle();
        extras.putParcelable(
                TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                Uri.parse("tel:" + number));
        telecom.addNewIncomingCall(handle, extras);
    }

    private final class TestConnection extends Connection {
        void markRinging() {
            setRinging();
        }

        @Override
        public void onAnswer() {
            setActive();
        }

        @Override
        public void onReject() {
            setDisconnected(new DisconnectCause(DisconnectCause.REJECTED));
            destroy();
        }

        @Override
        public void onDisconnect() {
            setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
            destroy();
        }

        @Override
        public void onShowIncomingCallUi() {
        }
    }
}
