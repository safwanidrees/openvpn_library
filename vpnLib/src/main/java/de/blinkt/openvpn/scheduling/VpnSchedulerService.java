package de.blinkt.openvpn.scheduling;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import de.blinkt.openvpn.R;
import de.blinkt.openvpn.core.OpenVPNService;

/**
 * Background service for handling VPN scheduling
 */
public class VpnSchedulerService extends Service {
    private static final String TAG = "VpnSchedulerService";
    private static final String CHANNEL_ID = "vpn_scheduler_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final String FLAG_PREFS = "vpn_scheduler_flags";
    private static final String KEY_SCHEDULED_DISCONNECT = "scheduled_disconnect";
    
    public static final String ACTION_SCHEDULE_CONNECT = "de.blinkt.openvpn.SCHEDULE_CONNECT";
    public static final String ACTION_SCHEDULE_DISCONNECT = "de.blinkt.openvpn.SCHEDULE_DISCONNECT";
    public static final String EXTRA_SCHEDULE_ID = "schedule_id";
    public static final String ACTION_SCHEDULED_EVENT = "de.blinkt.openvpn.SCHEDULED_EVENT"; // local broadcast
    
    private AlarmManager alarmManager;
    private SharedPreferences preferences;
    private SharedPreferences flagPreferences;
    private Gson gson;
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "VPN Scheduler Service created");
        alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        preferences = getSharedPreferences("vpn_schedules", Context.MODE_PRIVATE);
        flagPreferences = getSharedPreferences(FLAG_PREFS, Context.MODE_PRIVATE);
        gson = new Gson();
        createNotificationChannel();
        Log.d(TAG, "VPN Scheduler Service initialized successfully");
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "VPN Scheduler Service onStartCommand called");
        Log.d(TAG, "VPN Scheduler Service: Intent: " + intent);
        Log.d(TAG, "VPN Scheduler Service: Flags: " + flags + ", StartId: " + startId);
        
        if (intent != null) {
            String action = intent.getAction();
            String scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID);
            String intentAction = intent.getStringExtra("action");
            
            Log.d(TAG, "VPN Scheduler received action: " + action + ", scheduleId: " + scheduleId + ", intentAction: " + intentAction);
            Log.d(TAG, "VPN Scheduler: All intent extras: " + intent.getExtras());
            
            if (ACTION_SCHEDULE_CONNECT.equals(action)) {
                Log.d(TAG, "VPN Scheduler: Handling scheduled connect for schedule: " + scheduleId);
                handleScheduledConnect(scheduleId);
            } else if (ACTION_SCHEDULE_DISCONNECT.equals(action)) {
                Log.d(TAG, "VPN Scheduler: Handling scheduled disconnect for schedule: " + scheduleId);
                handleScheduledDisconnect(scheduleId);
            } else if ("schedule".equals(intentAction)) {
                VpnSchedule schedule = (VpnSchedule) intent.getSerializableExtra("schedule");
                if (schedule != null) {
                    Log.d(TAG, "VPN Scheduler: Scheduling new VPN connection for: " + schedule.getName());
                    scheduleVpn(schedule);
                }
            } else if ("cancel".equals(intentAction)) {
                String cancelScheduleId = intent.getStringExtra("scheduleId");
                if (cancelScheduleId != null) {
                    Log.d(TAG, "VPN Scheduler: Cancelling schedule: " + cancelScheduleId);
                    cancelSchedule(cancelScheduleId);
                }
            } else if ("update".equals(intentAction)) {
                VpnSchedule schedule = (VpnSchedule) intent.getSerializableExtra("schedule");
                if (schedule != null) {
                    Log.d(TAG, "VPN Scheduler: Updating schedule: " + schedule.getName());
                    saveSchedule(schedule);
                }
            }
        }
        
        // Keep service running
        Log.d(TAG, "VPN Scheduler: Starting foreground service");
        if (android.os.Build.VERSION.SDK_INT >= 34) { // Android 14 (API 34)
            // Android 14+ requires explicit foreground service type
            Log.d(TAG, "VPN Scheduler: Starting foreground service with dataSync type (Android 14+)");
            startForeground(NOTIFICATION_ID, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // Android 10+ supports foreground service types but not required
            Log.d(TAG, "VPN Scheduler: Starting foreground service with dataSync type (Android 10+)");
            startForeground(NOTIFICATION_ID, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            Log.d(TAG, "VPN Scheduler: Starting foreground service (Android 9 and below)");
            startForeground(NOTIFICATION_ID, createNotification());
        }
        Log.d(TAG, "VPN Scheduler: Foreground service started successfully");
        return START_STICKY;
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "VPN Scheduler",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Background VPN scheduling service");
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }
    
    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("VPN Scheduler")
                .setContentText("Monitoring scheduled VPN connections")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
    
    private void handleScheduledConnect(String scheduleId) {
        long currentTime = System.currentTimeMillis();
        Log.d(TAG, "VPN Scheduler: Handling scheduled connect for: " + scheduleId);
        Log.d(TAG, "VPN Scheduler: Triggered at UTC time: " + currentTime + " (" + new java.util.Date(currentTime) + ")");
        
        // Clear scheduled-disconnect suppression flag and notify app
        try {
            flagPreferences.edit().putBoolean(KEY_SCHEDULED_DISCONNECT, false).apply();
            Intent broadcast = new Intent(ACTION_SCHEDULED_EVENT);
            broadcast.putExtra("type", "connect");
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
            Log.d(TAG, "VPN Scheduler: Broadcasted scheduled connect event");
        } catch (Exception e) {
            Log.w(TAG, "VPN Scheduler: Failed to broadcast scheduled connect event: " + e.getMessage());
        }

        VpnSchedule schedule = getSchedule(scheduleId);
        if (schedule == null) {
            Log.e(TAG, "VPN Scheduler: Schedule not found: " + scheduleId);
            return;
        }
        
        Log.d(TAG, "VPN Scheduler: Starting VPN connection for schedule: " + schedule.getName());
        Log.d(TAG, "VPN Scheduler: Config: " + schedule.getConfig());
        Log.d(TAG, "VPN Scheduler: Username: " + schedule.getUsername());
        Log.d(TAG, "VPN Scheduler: Password: " + (schedule.getPassword() != null ? "[PROVIDED]" : "[NULL]"));
        Log.d(TAG, "VPN Scheduler: Bypass packages: " + schedule.getBypassPackages());
        
        // Validate VPN configuration
        Log.d(TAG, "VPN Scheduler: Starting validation checks...");
        
        if (schedule.getConfig() == null || schedule.getConfig().trim().isEmpty()) {
            Log.e(TAG, "VPN Scheduler: ERROR - VPN config is null or empty");
            return;
        }
        
        Log.d(TAG, "VPN Scheduler: Config validation passed");
        
        // Note: Username and password are optional in this app design
        if (schedule.getUsername() != null && !schedule.getUsername().trim().isEmpty()) {
            Log.d(TAG, "VPN Scheduler: Username provided: " + schedule.getUsername());
        } else {
            Log.d(TAG, "VPN Scheduler: No username provided (optional)");
        }
        
        if (schedule.getPassword() != null && !schedule.getPassword().trim().isEmpty()) {
            Log.d(TAG, "VPN Scheduler: Password provided");
        } else {
            Log.d(TAG, "VPN Scheduler: No password provided (optional)");
        }
        
        Log.d(TAG, "VPN Scheduler: All validation checks passed");
        
        // Clear all existing schedules for fresh start (prevent conflicts)
        Log.d(TAG, "VPN Scheduler: Clearing all existing schedules for fresh start");
        List<VpnSchedule> existingSchedules = getAllSchedules();
        for (VpnSchedule existingSchedule : existingSchedules) {
            cancelSchedule(existingSchedule.getId());
            Log.d(TAG, "VPN Scheduler: Cancelled existing schedule: " + existingSchedule.getId());
        }
        Log.d(TAG, "VPN Scheduler: Cleared " + existingSchedules.size() + " existing schedules");
        
        // Start VPN connection using the same method as normal connection
        Log.d(TAG, "VPN Scheduler: Starting VPN using OpenVpnApi.startVpn()");
        
        try {
            // Parse bypass packages from JSON string
            List<String> bypassPackagesList = new java.util.ArrayList<>();
            if (schedule.getBypassPackages() != null && !schedule.getBypassPackages().trim().isEmpty()) {
                try {
                    bypassPackagesList = new com.google.gson.Gson().fromJson(
                        schedule.getBypassPackages(), 
                        new com.google.gson.reflect.TypeToken<List<String>>(){}.getType()
                    );
                    if (bypassPackagesList == null) {
                        bypassPackagesList = new java.util.ArrayList<>();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "VPN Scheduler: Could not parse bypass packages: " + e.getMessage());
                    bypassPackagesList = new java.util.ArrayList<>();
                }
            }
            
            Log.d(TAG, "VPN Scheduler: Bypass packages list size: " + bypassPackagesList.size());
            
            // Use the same API as normal VPN connection
            de.blinkt.openvpn.OpenVpnApi.startVpn(
                this, 
                schedule.getConfig(), 
                schedule.getName(), 
                schedule.getUsername(), 
                schedule.getPassword(), 
                bypassPackagesList
            );
            
            Log.d(TAG, "VPN Scheduler: VPN connection started successfully using OpenVpnApi");
        } catch (Exception e) {
            Log.e(TAG, "VPN Scheduler: Error starting VPN with OpenVpnApi: " + e.getMessage());
            Log.e(TAG, "VPN Scheduler: Error type: " + e.getClass().getSimpleName());
            e.printStackTrace();
        }
        
        // Schedule disconnect if needed
        if (schedule.getDisconnectTimeUTC() > 0) {
            Log.d(TAG, "VPN Scheduler: Scheduling disconnect for: " + schedule.getName() + " at " + schedule.getDisconnectTimeUTC());
            scheduleDisconnect(schedule);
        }
    }
    
    private void handleScheduledDisconnect(String scheduleId) {
        long currentTime = System.currentTimeMillis();
        Log.d(TAG, "VPN Scheduler: Handling scheduled disconnect for: " + scheduleId);
        Log.d(TAG, "VPN Scheduler: Disconnect triggered at UTC time: " + currentTime + " (" + new java.util.Date(currentTime) + ")");
        
        // Set scheduled-disconnect suppression flag and notify app
        try {
            flagPreferences.edit().putBoolean(KEY_SCHEDULED_DISCONNECT, true).apply();
            Intent broadcast = new Intent(ACTION_SCHEDULED_EVENT);
            broadcast.putExtra("type", "disconnect");
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
            Log.d(TAG, "VPN Scheduler: Broadcasted scheduled disconnect event");
        } catch (Exception e) {
            Log.w(TAG, "VPN Scheduler: Failed to broadcast scheduled disconnect event: " + e.getMessage());
        }

        // Stop VPN connection using the same method as normal disconnect
        Log.d(TAG, "VPN Scheduler: Stopping VPN using OpenVPNThread.stop()");
        try {
            de.blinkt.openvpn.core.OpenVPNThread.stop();
            Log.d(TAG, "VPN Scheduler: VPN disconnect command sent successfully");
            
            // Clear all schedules to prevent auto-reconnect (same as manual disconnect)
            Log.d(TAG, "VPN Scheduler: Clearing all schedules to prevent auto-reconnect");
            List<VpnSchedule> allSchedules = getAllSchedules();
            for (VpnSchedule schedule : allSchedules) {
                cancelSchedule(schedule.getId());
                Log.d(TAG, "VPN Scheduler: Cancelled schedule: " + schedule.getId());
            }
            Log.d(TAG, "VPN Scheduler: Cleared " + allSchedules.size() + " schedules");
            
        } catch (Exception e) {
            Log.e(TAG, "VPN Scheduler: Error stopping VPN: " + e.getMessage());
            Log.e(TAG, "VPN Scheduler: Error type: " + e.getClass().getSimpleName());
            e.printStackTrace();
        }
    }
    
    private void scheduleDisconnect(VpnSchedule schedule) {
        Intent disconnectIntent = new Intent(this, VpnSchedulerService.class);
        disconnectIntent.setAction(ACTION_SCHEDULE_DISCONNECT);
        disconnectIntent.putExtra(EXTRA_SCHEDULE_ID, schedule.getId());
        
        PendingIntent pendingIntent = PendingIntent.getService(
            this,
            getDisconnectRequestCode(schedule.getId()),
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                schedule.getDisconnectTimeUTC(),
                pendingIntent
            );
        } else {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                schedule.getDisconnectTimeUTC(),
                pendingIntent
            );
        }
        
        Log.d(TAG, "Scheduled disconnect for: " + schedule.getId() + " at " + schedule.getDisconnectTimeUTC());
    }
    
    public void scheduleVpn(VpnSchedule schedule) {
        Log.d(TAG, "VPN Scheduler: Scheduling VPN connection for: " + schedule.getName());
        Log.d(TAG, "VPN Scheduler: Schedule ID: " + schedule.getId());
        Log.d(TAG, "VPN Scheduler: Is recurring: " + schedule.isRecurring());
        
        if (schedule.isRecurring()) {
            Log.d(TAG, "VPN Scheduler: Recurring schedule - Days mask: " + schedule.getRecurringDays());
            Log.d(TAG, "VPN Scheduler: Recurring schedule - Next connect time: " + schedule.getNextConnectTime() + " (" + new java.util.Date(schedule.getNextConnectTime()) + ")");
        } else {
            Log.d(TAG, "VPN Scheduler: One-time schedule - Connect time: " + schedule.getConnectTimeUTC() + " (" + new java.util.Date(schedule.getConnectTimeUTC()) + ")");
        }
        
        // Store schedule
        saveSchedule(schedule);
        Log.d(TAG, "VPN Scheduler: Schedule saved to preferences");
        
        // Schedule connect
        Intent connectIntent = new Intent(this, VpnSchedulerService.class);
        connectIntent.setAction(ACTION_SCHEDULE_CONNECT);
        connectIntent.putExtra(EXTRA_SCHEDULE_ID, schedule.getId());
        
        PendingIntent pendingIntent = PendingIntent.getService(
            this,
            getConnectRequestCode(schedule.getId()),
            connectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        long triggerTime = schedule.isRecurring() ? schedule.getNextConnectTime() : schedule.getConnectTimeUTC();
        long currentTime = System.currentTimeMillis();
        long timeUntilTrigger = triggerTime - currentTime;
        
        // Format UTC time properly
        java.text.SimpleDateFormat utcFormat = new java.text.SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss.SSS 'UTC'");
        utcFormat.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        
        Log.d(TAG, "VPN Scheduler: Current UTC time: " + currentTime + " (" + utcFormat.format(new java.util.Date(currentTime)) + ")");
        Log.d(TAG, "VPN Scheduler: Trigger UTC time: " + triggerTime + " (" + utcFormat.format(new java.util.Date(triggerTime)) + ")");
        Log.d(TAG, "VPN Scheduler: Time until trigger: " + timeUntilTrigger + "ms (" + (timeUntilTrigger / 1000) + " seconds)");
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            Log.d(TAG, "VPN Scheduler: Setting exact alarm with allowWhileIdle (Android 6+)");
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            );
        } else {
            Log.d(TAG, "VPN Scheduler: Setting exact alarm (Android 5 and below)");
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            );
        }
        
        Log.d(TAG, "VPN Scheduler: VPN scheduled successfully for: " + schedule.getId() + " at " + triggerTime);
    }
    
    
    public void cancelSchedule(String scheduleId) {
        Log.d(TAG, "VPN Scheduler: Cancelling schedule: " + scheduleId);
        
        // Cancel alarms
        Intent connectIntent = new Intent(this, VpnSchedulerService.class);
        connectIntent.setAction(ACTION_SCHEDULE_CONNECT);
        connectIntent.putExtra(EXTRA_SCHEDULE_ID, scheduleId);
        
        PendingIntent connectPending = PendingIntent.getService(
            this,
            getConnectRequestCode(scheduleId),
            connectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        Intent disconnectIntent = new Intent(this, VpnSchedulerService.class);
        disconnectIntent.setAction(ACTION_SCHEDULE_DISCONNECT);
        disconnectIntent.putExtra(EXTRA_SCHEDULE_ID, scheduleId);
        
        PendingIntent disconnectPending = PendingIntent.getService(
            this,
            getDisconnectRequestCode(scheduleId),
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        Log.d(TAG, "VPN Scheduler: Cancelling connect alarm for schedule: " + scheduleId);
        alarmManager.cancel(connectPending);
        Log.d(TAG, "VPN Scheduler: Cancelling disconnect alarm for schedule: " + scheduleId);
        alarmManager.cancel(disconnectPending);
        
        // Remove from storage
        Log.d(TAG, "VPN Scheduler: Removing schedule from storage: " + scheduleId);
        removeSchedule(scheduleId);
        
        Log.d(TAG, "VPN Scheduler: Schedule cancelled successfully: " + scheduleId);
    }
    
    private void saveSchedule(VpnSchedule schedule) {
        List<VpnSchedule> schedules = getAllSchedules();
        boolean found = false;
        
        for (int i = 0; i < schedules.size(); i++) {
            if (schedules.get(i).getId().equals(schedule.getId())) {
                schedules.set(i, schedule);
                found = true;
                break;
            }
        }
        
        if (!found) {
            schedules.add(schedule);
        }
        
        String json = gson.toJson(schedules);
        preferences.edit().putString("schedules", json).apply();
    }
    
    private VpnSchedule getSchedule(String scheduleId) {
        List<VpnSchedule> schedules = getAllSchedules();
        for (VpnSchedule schedule : schedules) {
            if (schedule.getId().equals(scheduleId)) {
                return schedule;
            }
        }
        return null;
    }
    
    /**
     * Get unique request code for connect alarms
     */
    private int getConnectRequestCode(String scheduleId) {
        // Use positive hash code to avoid collisions
        return Math.abs(scheduleId.hashCode());
    }
    
    /**
     * Get unique request code for disconnect alarms
     */
    private int getDisconnectRequestCode(String scheduleId) {
        // Use larger offset to ensure no collision with connect codes
        return Math.abs(scheduleId.hashCode()) + 10000;
    }

    private void removeSchedule(String scheduleId) {
        List<VpnSchedule> schedules = getAllSchedules();
        
        // Use iterator to remove items for API level compatibility
        java.util.Iterator<VpnSchedule> iterator = schedules.iterator();
        while (iterator.hasNext()) {
            VpnSchedule schedule = iterator.next();
            if (schedule.getId().equals(scheduleId)) {
                iterator.remove();
            }
        }
        
        String json = gson.toJson(schedules);
        preferences.edit().putString("schedules", json).apply();
    }
    
    public List<VpnSchedule> getAllSchedules() {
        String json = preferences.getString("schedules", "[]");
        Type listType = new TypeToken<List<VpnSchedule>>(){}.getType();
        return gson.fromJson(json, listType);
    }
}
