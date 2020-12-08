/*
 * Copyright (C) 2018 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *	http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.robertogl.settingsextra;

import android.content.Context;
import android.media.AudioManager;
import android.os.Vibrator;
import android.view.KeyEvent;
import android.util.Log;
import android.content.Intent;
import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;

import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;

import android.content.SharedPreferences;

import android.widget.Toast;

public class MainService extends AccessibilityService {
    private static final String TAG = "MainService";

    protected static final boolean DEBUG = false;

    // Slider key codes
    private static final int MODE_NORMAL = 603;
    private static final int MODE_VIBRATION = 602;
    private static final int MODE_SILENCE = 601;
    private static final int KEYCODE_APP_SELECT = 580;
    private static final int KEYCODE_BACK = 158;
    private static final int KEYCODE_F4 = 62;

    // Vibration duration in ms
    private static final int msSilentVibrationLenght = 300;
    private static final int msVibrateVibrationLenght = 200;

    private static final int msDoubleClickThreshold = 250;
    private long msDoubleClick = 0;

    private static final String TriStatePath = "/sys/devices/virtual/switch/tri-state-key/state";

    private boolean wasScreenOff = true;

    private long currentEventTime = 0;
    private long previousEventTime = 0;

    private static int clickToShutdown = 0;

    private Context mContext;
    private AudioManager mAudioManager;
    private Vibrator mVibrator;

    private CallRecording mCallRecording = new CallRecording();

    private NfcStatusMonitor mNfcMonitor = new NfcStatusMonitor();

    private BluetoothBatteryIcon mBluetoothBatteryIcon = new BluetoothBatteryIcon();

    private ConnectivityManagerExtra mConnectivityManagerExtra = new ConnectivityManagerExtra();

	private  SharedPreferences.OnSharedPreferenceChangeListener mPreferenceListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
		@Override
		public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
			if (key.equals("areWeAllowedToRecordCall")) {
				boolean areWeAllowedToRecordCall = prefs.getBoolean("areWeAllowedToRecordCall", false);
				if (DEBUG) Log.d(TAG, "Preference change received. areWeAllowedToRecordCall now is: " + areWeAllowedToRecordCall);
				if (areWeAllowedToRecordCall) {
					// Start the CallRecording service if the user enabled the service
					mCallRecording.onStartup(mContext);
				} else if (!mCallRecording.areWeRecordingACall) {
					// Stop the CallRecording service now
					mCallRecording.onClose();
				} else {
				    mCallRecording.stopListening = true;
					Toast.makeText(mContext, "Changes will be effective after the current call", Toast.LENGTH_LONG).show();
				}
            } else if (key.equals("dynamicModem")) {
                boolean dynamicModem = prefs.getBoolean("dynamicModem", false);
                if (dynamicModem) {
                    if (DEBUG) Log.d(TAG, "Starting connectivityManagerExtra service");
                    mConnectivityManagerExtra.onStartup(mContext);
                } else
                    mConnectivityManagerExtra.onClose();
            } else if (key.equals("preferred_network_mode_key_wifi") || key.equals("preferred_network_mode_key")) {
                // Settings changed for ConnectivityManagerExtra
                if (DEBUG) Log.d(TAG, "Network settings for ConnectivityManagerExtra changed");
                boolean dynamicModem = prefs.getBoolean("dynamicModem", false);
                if (dynamicModem) {
                    if (DEBUG) Log.d(TAG, "Updating network settings for ConnectivityManagerExtra");
                    mConnectivityManagerExtra.forceNetworkSettingsUpdate();
                }
            }
        }
	};

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mScreenStateReceiver);
        mBluetoothBatteryIcon.onClose();
        mCallRecording.onClose();
        mNfcMonitor.onClose();
        mConnectivityManagerExtra.onClose();
    }

	@Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        if (DEBUG) Log.d(TAG, "service is connected");
        mContext = this;
        mAudioManager = mContext.getSystemService(AudioManager.class);
        mVibrator = mContext.getSystemService(Vibrator.class);

		Context deviceProtectedContext = mContext.createDeviceProtectedStorageContext();
		SharedPreferences pref = deviceProtectedContext.getSharedPreferences(mContext.getPackageName() + "_preferences", MODE_PRIVATE);

		pref.registerOnSharedPreferenceChangeListener(mPreferenceListener);

        // Set the status at boot following the slider position
        // Do this in case the user changes the slider position while the phone is off, for example
        // Also, we solve an issue regarding the STREAM_MUSIC that was never mute at boot
        int tristate = Integer.parseInt(Utils.readFromFile(TriStatePath));
        if (DEBUG) Log.d(TAG, "Tri Key state: " + tristate);
        if (tristate == 1) {
            // Silent mode
            mAudioManager.setStreamMute(AudioManager.STREAM_MUSIC, true);
            mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_SILENT);
        } else if (tristate == 2) {
            // Vibration mode
            mAudioManager.setStreamMute(AudioManager.STREAM_MUSIC, false);
            mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_VIBRATE);
        } else if (tristate == 3) {
            // Normal mode
            mAudioManager.setStreamMute(AudioManager.STREAM_MUSIC, false);
            mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL);
        }

        // Register here to get the SCREEN_OFF event
        // Used to turn off the capacitive buttons backlight
        IntentFilter screenActionFilter = new IntentFilter();
        screenActionFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenActionFilter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(mScreenStateReceiver, screenActionFilter);

        // Enable the Dynamic Modem if the user enabled it
        boolean dynamicModem = pref.getBoolean("dynamicModem", false);
        if (dynamicModem) {
            if (DEBUG) Log.d(TAG, "Starting Dynamic Modem");
            // At the moment, ConnectivityManagerExtra only manages the Dynamic Modem
            mConnectivityManagerExtra.onStartup(this);
        }

        // Start the NFC tile monitoring service
		mNfcMonitor.onStartup(this);

		// Start the Bluetooth battery icon on the status bar monitoring service
		mBluetoothBatteryIcon.onStartup(this);

		// Start the CallRecording service if the user enabled the service
		boolean areWeAllowedToRecordCall = pref.getBoolean("areWeAllowedToRecordCall", false);
		if (areWeAllowedToRecordCall) {
		    if (DEBUG) Log.d(TAG, "Starting the CallRecording service");
			mCallRecording.onStartup(this);
		}
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent accessibilityEvent) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        return handleKeyEvent(event);
    }

    private BroadcastReceiver mScreenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_SCREEN_OFF:
                    if (DEBUG) Log.d(TAG, "Screen OFF");
                    clickToShutdown = 0;
                    if (DEBUG)
                        Log.d(TAG, "Always On Display is: " + Utils.isAlwaysOnDisplayEnabled(mContext));
                    if (Utils.isAlwaysOnDisplayEnabled(mContext))
                        Utils.writeToFile(Utils.dozeWakeupNode, "1", mContext);
                    Utils.setProp("sys.button_backlight.on", "false");
                    break;
                case Intent.ACTION_SCREEN_ON:
                    if (DEBUG) Log.d(TAG, "Screen ON");
                    if (Utils.isAlwaysOnDisplayEnabled(mContext))
                        Utils.writeToFile(Utils.dozeWakeupNode, "0", mContext);
                    break;
            }
        }
    };

    private boolean handleKeyEvent(KeyEvent event) {
        PowerManager manager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        int scanCode = event.getScanCode();
        if (DEBUG) Log.d(TAG, "key event detected: " + scanCode);
        long msPreviousEventMaxDistance = 1000;
        if (previousEventTime == 0)
            previousEventTime = System.currentTimeMillis() - msPreviousEventMaxDistance - 1;
        if (currentEventTime == 0) currentEventTime = System.currentTimeMillis();
        switch (scanCode) {
            case MODE_NORMAL:
                if (!Utils.isScreenOn(mContext)) {
                    previousEventTime = System.currentTimeMillis();
                    wasScreenOff = true;
                }
                currentEventTime = System.currentTimeMillis();
                if (currentEventTime - previousEventTime > msPreviousEventMaxDistance)
                    wasScreenOff = false;
                if (wasScreenOff) {
                    manager.goToSleep(SystemClock.uptimeMillis());
                    previousEventTime = System.currentTimeMillis();
                }
                if (mAudioManager.getRingerModeInternal() != AudioManager.RINGER_MODE_NORMAL) {
                    mAudioManager.setStreamMute(AudioManager.STREAM_MUSIC, false);
                    mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL);
                }
                return true;
            case MODE_VIBRATION:
                if (!Utils.isScreenOn(mContext)) {
                    previousEventTime = System.currentTimeMillis();
                    wasScreenOff = true;
                }
                currentEventTime = System.currentTimeMillis();
                if (currentEventTime - previousEventTime > msPreviousEventMaxDistance)
                    wasScreenOff = false;
                if (wasScreenOff) {
                    manager.goToSleep(SystemClock.uptimeMillis());
                    previousEventTime = System.currentTimeMillis();
                }
                if (mAudioManager.getRingerModeInternal() != AudioManager.RINGER_MODE_VIBRATE) {
                    mAudioManager.setStreamMute(AudioManager.STREAM_MUSIC, false);
                    mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_VIBRATE);
                    Utils.doHapticFeedback(mVibrator, msVibrateVibrationLenght);
                }
                return true;
            case MODE_SILENCE:
                if (!Utils.isScreenOn(mContext)) {
                    previousEventTime = System.currentTimeMillis();
                    wasScreenOff = true;
                }
                currentEventTime = System.currentTimeMillis();
                if (currentEventTime - previousEventTime > msPreviousEventMaxDistance)
                    wasScreenOff = false;
                if (wasScreenOff) {
                    manager.goToSleep(SystemClock.uptimeMillis());
                    previousEventTime = System.currentTimeMillis();
                }
                if (mAudioManager.getRingerModeInternal() != AudioManager.RINGER_MODE_SILENT) {
                    mAudioManager.setStreamMute(AudioManager.STREAM_MUSIC, true);
                    mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_SILENT);
                    Utils.doHapticFeedback(mVibrator, msSilentVibrationLenght);
                }
                return true;
            case KEYCODE_BACK:
            case KEYCODE_APP_SELECT:
                if (event.getAction() == 0) {
                    clickToShutdown += 1;
                    Utils.setProp("sys.button_backlight.on", "true");
                } else {
                    Handler handler = new Handler();
                    handler.postDelayed(new Runnable() {
                        public void run() {
                            clickToShutdown -= 1;
                            if (clickToShutdown <= 0) {
                                clickToShutdown = 0;
                                Utils.setProp("sys.button_backlight.on", "false");
                            }
                        }
                    }, 1500);
                }
                return false;
            case KEYCODE_F4:
                if (DEBUG) Log.d(TAG, "F4 detected");
                if (Integer.parseInt(Utils.readFromFile(Utils.dozeWakeupNode)) == 0) {
                    if (DEBUG) Log.d(TAG, "F4 ignored (not enabled)");
                    return false;
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    if (DEBUG) Log.d(TAG, "F4 UP detected");
                    if (doubleClick()) {
                        PowerManager.WakeLock wakeLock;
                        wakeLock = manager.newWakeLock(PowerManager.FULL_WAKE_LOCK |
                                PowerManager.ACQUIRE_CAUSES_WAKEUP |
                                PowerManager.ON_AFTER_RELEASE, "WakeLock");
                        wakeLock.acquire();
                        wakeLock.release();
                        wakeLock = null;
                        return true;
                    }
                }
                return true;
            default:
                return false;
        }
    }

    private boolean doubleClick() {
        boolean result = false;
        long thisTime = System.currentTimeMillis();

        if ((thisTime - msDoubleClick) < msDoubleClickThreshold) {
            if (DEBUG) Log.d(TAG, "doubleClick");
            result = true;
        } else {
            msDoubleClick = thisTime;
        }
        return result;
    }
}
