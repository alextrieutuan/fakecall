package com.alex.fakecall.call_ui;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.alex.fakecall.App;
import com.alex.fakecall.R;
import com.alex.fakecall.activities.BaseActivity;
import com.alex.fakecall.helper.RingtoneHelper;
import com.alex.fakecall.helper.CallLogHelper;
import com.alex.fakecall.helper.VibrationHelper;
import com.alex.fakecall.helper.WakeupHelper;
import com.alex.fakecall.models.Call;
import com.alex.fakecall.views.MyChronometer;

import java.lang.ref.WeakReference;

import butterknife.BindView;
import butterknife.OnClick;
import butterknife.Optional;


public abstract class BaseCallActivity extends BaseActivity implements SensorEventListener {
    @BindView(R.id.chronometer)
    MyChronometer chronometer;

    @BindView(R.id.mask)
    RelativeLayout maskLayout;

    @Nullable
    @BindView(R.id.tvName)
    TextView tvName;

    @Nullable
    @BindView(R.id.tvNumber)
    TextView tvNumber;

    private boolean isInCall = false;
    private boolean isPreview;
    protected Call mCall;

    private SensorManager mSensorManager;
    private Sensor mProximity;
    private boolean isScreenOff = false;

    private MyHandler mHandler;
    private static final int MAX_TIME_TO_MISSED_CALL = 60 * 1000;

    private NotificationManager mNotifyManager;
    private static final int NOTIFY_ID_INCOMING = 1;
    private static final int NOTIFY_ID_ONGOING = 2;

    private AudioManager mAudioManager;

    protected abstract void configureForInCall();

    protected abstract void configureForInComing();

    private static class MyHandler extends Handler {
        public static final int HANDLE_MSG_MISSED = 1;
        public static final int HANDLE_MSG_END_CALL = 2;

        private WeakReference<BaseCallActivity> mWeakAct;

        public MyHandler(BaseCallActivity act) {
            mWeakAct = new WeakReference<>(act);
        }

        @Override
        public void handleMessage(Message msg) {
            BaseCallActivity act = mWeakAct.get();
            if (act == null) return;
            switch (msg.what) {
                case HANDLE_MSG_END_CALL:
                    act.onEndCall();
                    break;
                case HANDLE_MSG_MISSED:
                    act.onMissedCall();
                    break;
            }
        }
    }

    @Override
    protected void onSetUp() {
        mCall = (Call) getIntent().getSerializableExtra(Call.KEY);
        if (mCall != null) {
            if (tvName != null)
                tvName.setText(mCall.getDisplayName());
            if (tvNumber != null)
                tvNumber.setText(mCall.getNumber());
        }

        isPreview = getIntent().getBooleanExtra("isPreview", false);

        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        mProximity = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);

        mNotifyManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        mAudioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        mHandler = new MyHandler(this);

        configureForInComing();
        mHandler.sendEmptyMessageDelayed(MyHandler.HANDLE_MSG_MISSED, MAX_TIME_TO_MISSED_CALL);

        RingtoneHelper.getInstance().playRingtone(this, Settings.System.DEFAULT_RINGTONE_URI, true);
        VibrationHelper.getInstance().vibrate(this, true);
        WakeupHelper.getInstance().wakeUp(this);
        App.GlobalVars.isInFakeCall = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        mSensorManager.registerListener(this, mProximity, SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mSensorManager.unregisterListener(this, mProximity);
    }

    protected void answerCall() {
        App.GlobalVars.isInFakeCall = true;
        isInCall = true;
        configureForInCall();
        mHandler.removeMessages(MyHandler.HANDLE_MSG_MISSED);
        mAudioManager.setMode(AudioManager.MODE_IN_CALL);
        mAudioManager.setSpeakerphoneOn(false);

        chronometer.setBase(SystemClock.elapsedRealtime());
        chronometer.start();

        RingtoneHelper.getInstance().stopRingtone();
        VibrationHelper.getInstance().cancelAll(this);
    }

    protected void onMissedCall() {
        if (!isPreview) {
            CallLogHelper.getInstance().writeMissedCall(this, mCall);
        }
        doWorksAfterEnd();
        finish();
    }

    @Optional
    @OnClick(R.id.btnEndCall)
    protected void onEndCall() {
        if (!isPreview) {
            CallLogHelper.getInstance().writeIncomingCall(this, mCall, chronometer.getDuration());
        }
        doWorksAfterEnd();
        finish();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!isInCall) return;
        if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            if (event.values[0] == 0) {
                if (isScreenOff) return;
                screenOff();
            } else {
                if (!isScreenOff) return;
                screenOn();
            }
        }
    }

    private void doWorksAfterEnd() {
        App.GlobalVars.isInFakeCall = true;
        mHandler.removeMessages(MyHandler.HANDLE_MSG_MISSED);
        mHandler.removeMessages(MyHandler.HANDLE_MSG_END_CALL);
        mNotifyManager.cancel(NOTIFY_ID_INCOMING);
        mNotifyManager.cancel(NOTIFY_ID_ONGOING);
        mAudioManager.setMode(AudioManager.MODE_NORMAL);
        RingtoneHelper.getInstance().stopRingtone();
        VibrationHelper.getInstance().cancelAll(this);
        WakeupHelper.getInstance().reset();
    }

    protected void showIncomingNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
        prepareNotification(builder);
        builder.setContentText("Incoming");
        mNotifyManager.notify(NOTIFY_ID_INCOMING, builder.build());
    }

    protected void showInCallNotification() {
        mNotifyManager.cancel(NOTIFY_ID_INCOMING);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
        prepareNotification(builder);
        builder.setContentText("Ongoing");
        builder.setUsesChronometer(true);
        mNotifyManager.notify(NOTIFY_ID_ONGOING, builder.build());
    }

    private void prepareNotification(NotificationCompat.Builder builder) {
        builder.setAutoCancel(false);
        builder.setOnlyAlertOnce(false);
        builder.setOngoing(true);
        builder.setDefaults(32);

        if (mCall != null) {
            builder.setTicker(mCall.getDisplayName());
            builder.setContentTitle(mCall.getDisplayName());
        }

        Intent intent = new Intent(this, getClass());
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        builder.setContentIntent(pendingIntent);

        builder.setSmallIcon(R.drawable.android_50x_ic_toolbar_add_call);
    }

    private void screenOff() {
        isScreenOff = true;
        try {
            if (maskLayout != null)
                maskLayout.setVisibility(View.VISIBLE);
            WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.flags |= WindowManager.LayoutParams.FLAG_FULLSCREEN;
            getWindow().setAttributes(attributes);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void screenOn() {
        isScreenOff = false;
        try {
            if (maskLayout != null)
                maskLayout.setVisibility(View.GONE);
            WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.flags &= -1025;
            getWindow().setAttributes(attributes);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                if (!isInCall) {
                    return true;
                }
                onEndCall();
                return true;
            case KeyEvent.KEYCODE_CALL:
                answerCall();
                return true;
            case KeyEvent.KEYCODE_ENDCALL:
                onEndCall();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    @Override
    protected void onCleanUp() {

    }
}
