package org.blitzortung.android.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.*;
import android.preference.PreferenceManager;
import android.util.Log;
import org.blitzortung.android.alarm.AlarmParameters;
import org.blitzortung.android.alarm.AlarmResult;
import org.blitzortung.android.alarm.AlertEvent;
import org.blitzortung.android.alarm.AlertHandler;
import org.blitzortung.android.alarm.factory.AlarmObjectFactory;
import org.blitzortung.android.alarm.object.AlarmStatus;
import org.blitzortung.android.location.LocationHandler;
import org.blitzortung.android.app.controller.NotificationHandler;
import org.blitzortung.android.app.view.PreferenceKey;
import org.blitzortung.android.data.DataChannel;
import org.blitzortung.android.data.DataHandler;
import org.blitzortung.android.data.provider.result.DataEvent;
import org.blitzortung.android.data.provider.result.ResultEvent;
import org.blitzortung.android.data.provider.result.StatusEvent;
import org.blitzortung.android.map.overlay.OwnLocationOverlay;
import org.blitzortung.android.protocol.Event;
import org.blitzortung.android.protocol.Listener;
import org.blitzortung.android.protocol.ListenerContainer;
import org.blitzortung.android.util.Period;

import java.util.HashSet;
import java.util.Set;

public class AppService extends Service implements Runnable, SharedPreferences.OnSharedPreferenceChangeListener, Listener {

    public static final String RETRIEVE_DATA_ACTION = "retrieveData";
    public static final String WAKE_LOCK_TAG = "boAndroidWakeLock";

    private final Handler handler;

    private int period;

    private int backgroundPeriod;

    private final Period updatePeriod;

    private boolean updateParticipants;

    private boolean enabled;

    private DataHandler dataHandler;
    private AlertHandler alertHandler;
    private LocationHandler locationHandler;

    private final IBinder binder = new DataServiceBinder();

    private AlarmManager alarmManager;

    private PendingIntent pendingIntent;

    private PowerManager.WakeLock wakeLock;

    ListenerContainer<DataEvent> dataListenerContainer = new ListenerContainer<DataEvent>() {
        @Override
        public void addedFirstListener() {
            discardAlarm();
            onResume();
        }

        @Override
        public void removedLastListener() {
            onPause();
            createAlarm();
        }
    };

    ListenerContainer<AlertEvent> alertListenerContainer = new ListenerContainer<AlertEvent>() {
        @Override
        public void addedFirstListener() {
            alertHandler.setAlertListener(AppService.this);
        }

        @Override
        public void removedLastListener() {
            alertHandler.unsetAlertListener();
        }
    };

    @SuppressWarnings("UnusedDeclaration")
    public AppService() {
        this(new Handler(), new Period());
        Log.d(Main.LOG_TAG, "AppService() created with new handler");
    }

    protected AppService(Handler handler, Period updatePeriod) {
        Log.d(Main.LOG_TAG, "AppService() create");
        this.handler = handler;
        this.updatePeriod = updatePeriod;
    }

    public int getPeriod() {
        return period;
    }

    public int getBackgroundPeriod() {
        return backgroundPeriod;
    }

    public long getLastUpdate() {
        return updatePeriod.getLastUpdateTime();
    }

    public void reloadData() {
        dataListenerContainer.broadcast(DataHandler.CLEAR_DATA_EVENT);

        if (isEnabled()) {
            restart();
        } else {
            Set<DataChannel> updateTargets = new HashSet<DataChannel>();
            updateTargets.add(DataChannel.STROKES);
            dataHandler.updateData(updateTargets);
        }
    }

    public DataHandler getDataHandler() {
        return dataHandler;
    }

    public boolean isAlertEnabled() {
        return alertHandler != null ? alertHandler.isAlertEnabled() : false;
    }

    public AlarmResult getAlarmResult() {
        return alertHandler.getAlarmResult();
    }

    public AlertHandler getAlertHandler() {
        return alertHandler;
    }

    @Override
    public void onEvent(Event event) {
        if (event instanceof DataEvent) {
            onDataEvent((DataEvent) event);
        }
    }

    private void onDataEvent(DataEvent result) {

        if (!dataListenerContainer.isEmpty()) {
            dataListenerContainer.storeAndBroadcast(result);
        }

        if (result instanceof ResultEvent) {
            checkForWarning((ResultEvent) result);
        }

        releaseWakeLock();
    }

    private void checkForWarning(ResultEvent result) {
        if (!result.hasFailed() && result.containsRealtimeData()) {
            alertHandler.checkStrokes(result.getStrokes());
        } else {
            alertHandler.invalidateAlert();
        }
    }

    public void addDataListener(Listener dataListener) {
        dataListenerContainer.addListener(dataListener);
    }

    public void removeDataListener(Listener dataListener) {
        dataListenerContainer.removeListener(dataListener);
    }

    public void addAlertListener(Listener alertListener) {
        alertListenerContainer.addListener(alertListener);
    }

    public void removeAlertListener(Listener alertListener) {
        alertListenerContainer.removeListener(alertListener);
    }

    public AlarmStatus getAlarmStatus() {
        return alertHandler.getAlarmStatus();
    }

    public void removeLocationListener(Listener listener) {
        locationHandler.removeUpdates(listener);
    }

    public void addLocationListener(Listener locationListener) {
        locationHandler.requestUpdates(locationListener);
    }

    public AlertEvent getAlertEvent() {
        return alertHandler.getAlertEvent();
    }

    public class DataServiceBinder extends Binder {
        AppService getService() {
            Log.d(Main.LOG_TAG, "DataServiceBinder.getService() " + AppService.this);
            return AppService.this;
        }
    }

    @Override
    public void onCreate() {
        Log.i(Main.LOG_TAG, "AppService.onCreate()");
        super.onCreate();

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferences.registerOnSharedPreferenceChangeListener(this);

        if (wakeLock == null) {
            Log.d(Main.LOG_TAG, "AppService.onCreate() create wakelock");
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG);
        }

        if (dataHandler == null) {
            dataHandler = new DataHandler(wakeLock, preferences, getPackageInfo());
            dataHandler.setDataListener(this);
        }

        onSharedPreferenceChanged(preferences, PreferenceKey.QUERY_PERIOD);
        onSharedPreferenceChanged(preferences, PreferenceKey.ALERT_ENABLED);
        onSharedPreferenceChanged(preferences, PreferenceKey.BACKGROUND_QUERY_PERIOD);
        onSharedPreferenceChanged(preferences, PreferenceKey.SHOW_PARTICIPANTS);

        locationHandler = new LocationHandler(this, preferences);
        AlarmParameters alarmParameters = new AlarmParameters();
        alarmParameters.updateSectorLabels(this);
        alertHandler = new AlertHandler(locationHandler, preferences, this,
                (Vibrator) this.getSystemService(Context.VIBRATOR_SERVICE),
                new NotificationHandler(this),
                new AlarmObjectFactory(), alarmParameters);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(Main.LOG_TAG, "AppService.onStartCommand() startId: " + startId + " " + intent);

        if (intent != null && RETRIEVE_DATA_ACTION.equals(intent.getAction())) {
            acquireWakeLock();

            Log.v(Main.LOG_TAG, "AppService.onStartCommand() acquired wake lock " + wakeLock);

            handler.removeCallbacks(this);
            handler.post(this);
        }

        if (!dataListenerContainer.isEmpty()) {
            createAlarm();
        }

        return START_STICKY;
    }

    private void acquireWakeLock() {
        wakeLock.acquire(30000);
    }

    public void releaseWakeLock() {
        if (wakeLock.isHeld()) {
            try {
                wakeLock.release();
                Log.v(Main.LOG_TAG, "AppService.releaseWakeLock() " + wakeLock);
            } catch (RuntimeException e) {
                Log.v(Main.LOG_TAG, "AppService.releaseWakeLock() failed", e);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(Main.LOG_TAG, "AppService.onBind() " + intent);

        return binder;
    }

    @Override
    public void run() {

        if (dataListenerContainer.isEmpty()) {
            Log.v(Main.LOG_TAG, "AppService.run() in background");

            dataHandler.updateDatainBackground();
        } else {
            releaseWakeLock();

            long currentTime = Period.getCurrentTime();
            if (dataHandler != null) {
                Set<DataChannel> updateTargets = new HashSet<DataChannel>();

                if (updatePeriod.shouldUpdate(currentTime, period)) {
                    updatePeriod.setLastUpdateTime(currentTime);
                    updateTargets.add(DataChannel.STROKES);

                    if (updateParticipants && updatePeriod.isNthUpdate(10)) {
                        updateTargets.add(DataChannel.PARTICIPANTS);
                    }
                }

                if (!updateTargets.isEmpty()) {
                    dataHandler.updateData(updateTargets);
                }

                final String statusString = "" + updatePeriod.getCurrentUpdatePeriod(currentTime, period) + "/" + period;
                dataListenerContainer.broadcast(new StatusEvent(statusString));
            }
            // Schedule the next update
            handler.postDelayed(this, 1000);
        }
    }

    public void restart() {
        updatePeriod.restart();
    }

    public void onResume() {
        if (dataHandler.isRealtime()) {
            Log.v(Main.LOG_TAG, "AppService.onResume() enable");
            locationHandler.requestUpdates(this);
            enable();
        } else {
            Log.v(Main.LOG_TAG, "AppService.onResume() do not enable");
        }
    }

    public boolean onPause() {
        Log.v(Main.LOG_TAG, "AppService.onPause() remove callback");
        handler.removeCallbacks(this);
        locationHandler.removeUpdates(this);

        return true;
    }

    @Override
    public void onDestroy() {
        Log.v(Main.LOG_TAG, "AppService.onDestroy()");
        super.onDestroy();
    }

    public void enable() {
        handler.removeCallbacks(this);
        handler.post(this);
        enabled = true;
    }

    public boolean isEnabled() {
        return enabled;
    }

    protected void disable() {
        enabled = false;
        handler.removeCallbacks(this);
    }

    public void setDataHandler(DataHandler dataHandler) {
        this.dataHandler = dataHandler;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String keyString) {
        onSharedPreferenceChanged(sharedPreferences, PreferenceKey.fromString(keyString));
    }

    private void onSharedPreferenceChanged(SharedPreferences sharedPreferences, PreferenceKey key) {
        switch (key) {
            case QUERY_PERIOD:
                period = Integer.parseInt(sharedPreferences.getString(key.toString(), "60"));
                break;

            case BACKGROUND_QUERY_PERIOD:
                int previousBackgroundPeriod = backgroundPeriod;
                backgroundPeriod = Integer.parseInt(sharedPreferences.getString(key.toString(), "0"));

                if (dataListenerContainer.isEmpty() && isAlertEnabled()) {
                    if (previousBackgroundPeriod == 0 && backgroundPeriod > 0) {
                        Log.v(Main.LOG_TAG, String.format("AppService.onSharedPreferenceChanged() create alarm with backgroundPeriod=%d", backgroundPeriod));
                        createAlarm();
                    } else if (previousBackgroundPeriod > 0 && backgroundPeriod == 0) {
                        discardAlarm();
                        Log.v(Main.LOG_TAG, String.format("AppService.onSharedPreferenceChanged() discard alarm", backgroundPeriod));
                    }
                } else {
                    Log.v(Main.LOG_TAG, String.format("AppService.onSharedPreferenceChanged() backgroundPeriod=%d", backgroundPeriod));
                }
                break;

            case SHOW_PARTICIPANTS:
                updateParticipants = sharedPreferences.getBoolean(key.toString(), true);
                break;
        }
    }

    private void createAlarm() {
        discardAlarm();

        if (dataListenerContainer.isEmpty() && backgroundPeriod > 0) {
            Log.v(Main.LOG_TAG, String.format("AppService.createAlarm() %d", backgroundPeriod));
            Intent intent = new Intent(this, AppService.class);
            intent.setAction(RETRIEVE_DATA_ACTION);
            pendingIntent = PendingIntent.getService(this, 0, intent, 0);
            alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (alarmManager != null) {
                alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, 0, backgroundPeriod * 1000, pendingIntent);
            } else {
                Log.e(Main.LOG_TAG, "AppService.createAlarm() failed");
            }
        }
    }

    private void discardAlarm() {
        if (alarmManager != null) {
            Log.v(Main.LOG_TAG, "AppService.discardAlarm()");
            alarmManager.cancel(pendingIntent);
            pendingIntent.cancel();

            pendingIntent = null;
            alarmManager = null;
        }
    }

    private PackageInfo getPackageInfo() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
