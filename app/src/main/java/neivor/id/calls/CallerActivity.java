package neivor.id.calls;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.app.NotificationCompat;
import androidx.core.content.PermissionChecker;

import com.google.firebase.messaging.FirebaseMessaging;
import com.plivo.endpoint.Incoming;
import com.plivo.endpoint.Outgoing;

import neivor.id.calls.PlivoBackEnd.STATE;

import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

import static neivor.id.calls.Utils.HH_MM_SS;
import static neivor.id.calls.Utils.MM_SS;

public class CallerActivity extends AppCompatActivity implements PlivoBackEnd.BackendListener {

    private static final int PERMISSIONS_REQUEST_CODE = 21;
    private Timer callTimer;
    private int tick;
    private boolean isSpeakerOn = false, isHoldOn = false, isMuteOn = false;
    private Object callData;
    private Vibrator vibrator;
    private String phoneNumber;
    private String callerName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_caller);
        vibrator = (Vibrator) this.getSystemService(Context.VIBRATOR_SERVICE);
        getIntentData();
        requestPermissions();
    }

    private void getIntentData() {
        if (getIntent().hasExtra("phoneNumber")) {
            phoneNumber = getIntent().getStringExtra("phoneNumber");
        } else {
            phoneNumber = "";
        }
        if (getIntent().hasExtra("username")) {
            Utils.setUsername(getIntent().getStringExtra("username"));
        }
        if (getIntent().hasExtra("password")) {
            Utils.setPassword(getIntent().getStringExtra("password"));
        }
        if (getIntent().hasExtra("callerName")) {
            callerName = getIntent().getStringExtra("callerName");
        }
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (PermissionChecker.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PermissionChecker.PERMISSION_GRANTED) {
                init();
            } else {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS_REQUEST_CODE);
            }
        } else {
            init();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String action = intent.getAction();
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Constants.ANSWER_ACTION.equals(action)) {
            answerCall();
            notificationManager.cancel(Constants.NOTIFICATION_ID);
            vibrator.cancel();

        } else if (Constants.REJECT_ACTION.equals(action)) {
            rejectCall();
            notificationManager.cancel(Constants.NOTIFICATION_ID);
            vibrator.cancel();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (grantResults != null && grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                init();
            }
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (getCurrentFocus() != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        }
        return super.dispatchTouchEvent(ev);
    }

    private void init() {
        registerBackendListener();
        loginWithToken();
    }

    private void registerBackendListener() {
        ((NeivorCallsAplication) getApplication()).backend().setListener(this);
        Utils.setBackendListener(this);
    }

    private void loginWithToken() {
        if (Utils.getLoggedinStatus()) {
            updateUI(STATE.IDLE, null);
            callData = Utils.getIncoming();
            if (callData != null) {
                showInCallUI(STATE.RINGING, Utils.getIncoming());
            }
        } else {
            FirebaseMessaging.getInstance().getToken().addOnSuccessListener(this, instanceIdResult ->
                    ((NeivorCallsAplication) getApplication()).backend().login(instanceIdResult));
        }
    }

    /**
     * Display & Handle Outgoing Calls
     *
     * @param state
     * @param outgoing
     */
    private void showOutCallUI(STATE state, Outgoing outgoing) {

        String title = state.name();
        TextView callerState;
        switch (state) {
            case IDLE:
                TextView callerName = findViewById(R.id.caller_name);
                callerState = findViewById(R.id.caller_state);
                callerName.setText(this.phoneNumber);
                callerState.setText(title);
                break;
            case RINGING:
                setContentView(R.layout.call);
                callerState = findViewById(R.id.caller_state);
                callerState.setText(Constants.RINGING_LABEL);
                TextView callerNameTextView = findViewById(R.id.caller_name);
                callerNameTextView.setText(this.callerName);
                break;
            case ANSWERED:
                startTimer();
                break;
            case HANGUP:
            case REJECTED:
                cancelTimer();
                setContentView(R.layout.activity_caller);
                updateUI(STATE.IDLE, null);
                break;
        }
    }

    /**
     * Display & Handle Incoming Calls
     *
     * @param state
     * @param incoming
     */
    private void showInCallUI(STATE state, Incoming incoming) {

        String title = (incoming != null ? Utils.from(incoming.getFromContact(), incoming.getFromSip()) : "");

        switch (state) {
            case ANSWERED:
                EditText phoneNumberText = (EditText) findViewById(R.id.call_text);
                String phoneNum = phoneNumberText.getText().toString();
                setContentView(R.layout.call);
                startTimer();
                break;

            case RINGING:
                notificationDialog(title, incoming);
                break;
            case HANGUP:
                cancelTimer();
                setContentView(R.layout.activity_caller);
                updateUI(STATE.IDLE, null);
                break;
            case REJECTED:
                removeNotification(Constants.NOTIFICATION_ID);
                break;
        }
    }

    private void notificationDialog(String title, Incoming incoming) {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        String NOTIFICATION_CHANNEL_ID = Constants.NOTIFICATION_CHANNEL;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            @SuppressLint("WrongConstant") NotificationChannel notificationChannel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, "My Notifications", NotificationManager.IMPORTANCE_MAX);
            // Configure the notification channel.
            notificationChannel.setDescription(Constants.NOTIFICATION_DESCRIPTION);
            notificationChannel.enableLights(true);
            notificationChannel.setLightColor(Color.RED);
            notificationChannel.setVibrationPattern(new long[]{0, 1000, 500, 1000});
            notificationChannel.enableVibration(true);
            notificationManager.createNotificationChannel(notificationChannel);
        }

        Intent answerIntent = new Intent(this, CallerActivity.class);
        answerIntent.setAction(Constants.ANSWER_ACTION);
        PendingIntent AcceptIntent = PendingIntent.getActivity(this, 0, answerIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_ONE_SHOT);
        Intent rejectIntent = new Intent(this, CallerActivity.class);
        rejectIntent.setAction(Constants.REJECT_ACTION);
        PendingIntent RejectIntent = PendingIntent.getActivity(this, 0, rejectIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_ONE_SHOT);

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID);
        notificationBuilder.setAutoCancel(true)
                .setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE)
                .setCategory(Notification.CATEGORY_CALL)
                .setWhen(System.currentTimeMillis())
                .setSmallIcon(R.mipmap.ic_launcher)
                .setTicker(Constants.NOTIFICATION_CHANNEL)
                .setPriority(Notification.PRIORITY_MAX)
                .setContentTitle(Constants.NOTIFICATION_DESCRIPTION)
                .setContentText(title)
                .addAction(android.R.drawable.ic_menu_delete, getString(R.string.reject), RejectIntent)
                .addAction(android.R.drawable.ic_menu_call, getString(R.string.answer), AcceptIntent)
                .setOngoing(true)
                .setVibrate(new long[]{0, 100, 500, 100, 500, 100, 500, 100, 500, 100, 500})
                .setContentInfo(Constants.NOTIFICATION_DESCRIPTION);
        notificationManager.notify(0, notificationBuilder.build());
        vibrator.vibrate(new long[]{1000, 1000, 1000, 1000, 1000}, 3);
    }

    private void removeNotification(int id) {
        vibrator.cancel();
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(id);
    }

    private void startTimer() {
        cancelTimer();

        callTimer = new Timer(false);
        callTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                runOnUiThread(() -> {
                    int hours = (int) TimeUnit.SECONDS.toHours(tick);
                    int minutes = (int) TimeUnit.SECONDS.toMinutes(tick -= TimeUnit.HOURS.toSeconds(hours));
                    int seconds = (int) (tick - TimeUnit.MINUTES.toSeconds(minutes));
                    String text = hours > 0 ? String.format(HH_MM_SS, hours, minutes, seconds) : String.format(MM_SS, minutes, seconds);
                    TextView timerTextView = findViewById(R.id.caller_state);
                    if (timerTextView != null) {
                        timerTextView.setVisibility(View.VISIBLE);
                        timerTextView.setText(text);
                        tick++;
                    }
                });
            }
        }, 100, TimeUnit.SECONDS.toMillis(1));
    }

    private void cancelTimer() {
        if (callTimer != null) callTimer.cancel();
        tick = 0;
    }

    private void makeCall() {
        Outgoing outgoing = ((NeivorCallsAplication) getApplication()).backend().getOutgoing();
        if (outgoing != null) {
            outgoing.call(this.phoneNumber);
        }
    }

    public void onClickBtnMakeCall(View view) {
        EditText phoneNumberText = (EditText) findViewById(R.id.call_text);
        String phoneNumber = phoneNumberText.getText().toString();
        if (phoneNumber.matches("")) {
            Toast.makeText(this, Constants.OUTGOING_CALL_DIAL_HINT, Toast.LENGTH_SHORT).show();
            return;
        }
    }

    public void onClickBtnEndCall(View view) {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        cancelTimer();
        endCall();
        isSpeakerOn = false;
        isHoldOn = false;
        isMuteOn = false;
        audioManager.setSpeakerphoneOn(isSpeakerOn);
        setContentView(R.layout.activity_caller);
        updateUI(STATE.IDLE, null);
    }

    public void endCall() {
        if (callData != null) {
            if (callData instanceof Outgoing) {
                ((Outgoing) callData).hangup();
                finish();
            } else {
                ((Incoming) callData).hangup();
                finish();
            }
        }
    }

    public void answerCall() {
        if (callData != null) {
            if (callData instanceof Incoming) {
                ((Incoming) callData).answer();
                updateUI(STATE.ANSWERED, callData);
            }
        }
    }

    public void rejectCall() {
        if (callData != null) {
            if (callData instanceof Incoming) {
                ((Incoming) callData).reject();
            }
        }
    }

    public void onClickBtnSpeaker(View view) {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        ImageButton btn = (ImageButton) findViewById(R.id.speaker);
        if (isSpeakerOn) {
            isSpeakerOn = false;
            btn.setImageResource(R.drawable.speaker);
            audioManager.setMode(AudioManager.MODE_IN_CALL);
            audioManager.setMode(AudioManager.MODE_NORMAL);
        } else {
            isSpeakerOn = true;
            btn.setImageResource(R.drawable.speaker_selected);
            audioManager.setMode(AudioManager.MODE_NORMAL);
            audioManager.setMode(AudioManager.MODE_IN_CALL);
        }
        audioManager.setSpeakerphoneOn(isSpeakerOn);
    }

    public void onClickBtnHold(View view) {
        ImageButton btn = (ImageButton) findViewById(R.id.hold);
        if (isHoldOn) {
            isHoldOn = false;
            btn.setImageResource(R.drawable.hold);
            unHoldCall();
        } else {
            isHoldOn = true;
            btn.setImageResource(R.drawable.hold_selected);
            holdCall();
        }
    }

    public void holdCall() {
        if (callData != null) {
            if (callData instanceof Outgoing) {
                ((Outgoing) callData).hold();
            } else {
                ((Incoming) callData).hold();
            }
        }
    }

    public void unHoldCall() {
        if (callData != null) {
            if (callData instanceof Outgoing) {
                ((Outgoing) callData).unhold();
            } else {
                ((Incoming) callData).unhold();
            }
        }
    }


    public void onClickBtnMute(View view) {
        ImageButton btn = (ImageButton) findViewById(R.id.mute);
        if (isMuteOn) {
            isMuteOn = false;
            btn.setImageResource(R.drawable.mute);
            unMuteCall();
        } else {
            isMuteOn = true;
            btn.setImageResource(R.drawable.mute_selected);
            muteCall();
        }
    }

    public void muteCall() {
        if (callData != null) {
            if (callData instanceof Outgoing) {
                ((Outgoing) callData).mute();
            } else {
                ((Incoming) callData).mute();
            }
        }
    }

    public void unMuteCall() {
        if (callData != null) {
            if (callData instanceof Outgoing) {
                ((Outgoing) callData).unmute();
            } else {
                ((Incoming) callData).unmute();
            }
        }
    }

    private void updateUI(PlivoBackEnd.STATE state, Object data) {
        callData = data;
        if (state.equals(STATE.REJECTED) || state.equals(STATE.HANGUP) || state.equals(STATE.INVALID)) {
            if (data != null) {
                if (data instanceof Outgoing) {
                    // handle outgoing
                    showOutCallUI(state, (Outgoing) data);
                } else {
                    // handle incoming
                    showInCallUI(state, (Incoming) data);
                }
            }
        } else {

            if (findViewById(R.id.call_btn) == null || findViewById(R.id.logged_in_as) == null || findViewById(R.id.logging_in_label) == null) {
                if (data != null) {
                    if (data instanceof Outgoing) {
                        // handle outgoing
                        showOutCallUI(state, (Outgoing) data);
                    } else {
                        // handle incoming
                        showInCallUI(state, (Incoming) data);
                    }
                }
            } else {
                findViewById(R.id.call_btn).setEnabled(true);

                if (data != null) {
                    if (data instanceof Outgoing) {
                        // handle outgoing
                        showOutCallUI(state, (Outgoing) data);
                    } else {
                        // handle incoming
                        showInCallUI(state, (Incoming) data);
                    }
                }
            }
        }
    }

    @Override
    public void onLogin(boolean success) {
        runOnUiThread(() -> {
            if (success) {
                setContentView(R.layout.call);
                TextView callerNameTextView = findViewById(R.id.caller_name);
                callerNameTextView.setText(callerName);
                makeCall();
            } else {
                Toast.makeText(this, R.string.login_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onLogout() {
    }

    @Override
    public void onIncomingCall(Incoming data, PlivoBackEnd.STATE callState) {
        runOnUiThread(() -> updateUI(callState, data));
    }

    @Override
    public void onOutgoingCall(Outgoing data, PlivoBackEnd.STATE callState) {
        runOnUiThread(() -> updateUI(callState, data));
    }

    @Override
    public void onIncomingDigit(String digit) {
        runOnUiThread(() -> Toast.makeText(this, String.format(getString(R.string.dtmf_received), digit), Toast.LENGTH_SHORT).show());
    }

    @Override
    public void mediaMetrics(HashMap messageTemplate) {

    }
}
