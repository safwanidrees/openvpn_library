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
    
    public static final String ACTION_SCHEDULE_CONNECT = "de.blinkt.openvpn.SCHEDULE_CONNECT";
    public static final String ACTION_SCHEDULE_DISCONNECT = "de.blinkt.openvpn.SCHEDULE_DISCONNECT";
    public static final String EXTRA_SCHEDULE_ID = "schedule_id";
    
    private AlarmManager alarmManager;
    private SharedPreferences preferences;
    private Gson gson;
    
    @Override
    public void onCreate() {
        super.onCreate();
        alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        preferences = getSharedPreferences("vpn_schedules", Context.MODE_PRIVATE);
        gson = new Gson();
        createNotificationChannel();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            String scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID);
            String intentAction = intent.getStringExtra("action");
            
            if (ACTION_SCHEDULE_CONNECT.equals(action)) {
                handleScheduledConnect(scheduleId);
            } else if (ACTION_SCHEDULE_DISCONNECT.equals(action)) {
                handleScheduledDisconnect(scheduleId);
            } else if ("schedule".equals(intentAction)) {
                VpnSchedule schedule = (VpnSchedule) intent.getSerializableExtra("schedule");
                if (schedule != null) {
                    scheduleVpn(schedule);
                }
            } else if ("cancel".equals(intentAction)) {
                String cancelScheduleId = intent.getStringExtra("scheduleId");
                if (cancelScheduleId != null) {
                    cancelSchedule(cancelScheduleId);
                }
            } else if ("update".equals(intentAction)) {
                VpnSchedule schedule = (VpnSchedule) intent.getSerializableExtra("schedule");
                if (schedule != null) {
                    saveSchedule(schedule);
                }
            }
        }
        
        // Keep service running
        if (android.os.Build.VERSION.SDK_INT >= 34) { // Android 14 (API 34)
            // Android 14+ requires explicit foreground service type
            startForeground(NOTIFICATION_ID, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // Android 10+ supports foreground service types but not required
            startForeground(NOTIFICATION_ID, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, createNotification());
        }
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
        Log.d(TAG, "Handling scheduled connect for: " + scheduleId);
        
        VpnSchedule schedule = getSchedule(scheduleId);
        if (schedule == null) {
            Log.e(TAG, "Schedule not found: " + scheduleId);
            return;
        }
        
        // Start VPN connection
        Intent vpnIntent = new Intent(this, OpenVPNService.class);
        vpnIntent.setAction(OpenVPNService.START_SERVICE);
        vpnIntent.putExtra("config", schedule.getConfig());
        vpnIntent.putExtra("name", schedule.getName());
        vpnIntent.putExtra("username", schedule.getUsername());
        vpnIntent.putExtra("password", schedule.getPassword());
        vpnIntent.putExtra("bypassPackages", schedule.getBypassPackages());
        startService(vpnIntent);
        
        // Schedule disconnect if needed
        if (schedule.getDisconnectTimeUTC() > 0) {
            scheduleDisconnect(schedule);
        }
    }
    
    private void handleScheduledDisconnect(String scheduleId) {
        Log.d(TAG, "Handling scheduled disconnect for: " + scheduleId);
        
        // Stop VPN connection
        Intent disconnectIntent = new Intent(this, OpenVPNService.class);
        disconnectIntent.setAction(OpenVPNService.DISCONNECT_VPN);
        startService(disconnectIntent);
    }
    
    private void scheduleDisconnect(VpnSchedule schedule) {
        Intent disconnectIntent = new Intent(this, VpnSchedulerService.class);
        disconnectIntent.setAction(ACTION_SCHEDULE_DISCONNECT);
        disconnectIntent.putExtra(EXTRA_SCHEDULE_ID, schedule.getId());
        
        PendingIntent pendingIntent = PendingIntent.getService(
            this,
            schedule.getId().hashCode() + 1000, // Different request code for disconnect
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
        // Store schedule
        saveSchedule(schedule);
        
        // Schedule connect
        Intent connectIntent = new Intent(this, VpnSchedulerService.class);
        connectIntent.setAction(ACTION_SCHEDULE_CONNECT);
        connectIntent.putExtra(EXTRA_SCHEDULE_ID, schedule.getId());
        
        PendingIntent pendingIntent = PendingIntent.getService(
            this,
            schedule.getId().hashCode(),
            connectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        long triggerTime = schedule.isRecurring() ? schedule.getNextConnectTime() : schedule.getConnectTimeUTC();
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            );
        } else {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            );
        }
        
        Log.d(TAG, "Scheduled VPN for: " + schedule.getId() + " at " + triggerTime);
    }
    
    public void cancelSchedule(String scheduleId) {
        // Cancel alarms
        Intent connectIntent = new Intent(this, VpnSchedulerService.class);
        connectIntent.setAction(ACTION_SCHEDULE_CONNECT);
        connectIntent.putExtra(EXTRA_SCHEDULE_ID, scheduleId);
        
        PendingIntent connectPending = PendingIntent.getService(
            this,
            scheduleId.hashCode(),
            connectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        Intent disconnectIntent = new Intent(this, VpnSchedulerService.class);
        disconnectIntent.setAction(ACTION_SCHEDULE_DISCONNECT);
        disconnectIntent.putExtra(EXTRA_SCHEDULE_ID, scheduleId);
        
        PendingIntent disconnectPending = PendingIntent.getService(
            this,
            scheduleId.hashCode() + 1000,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        alarmManager.cancel(connectPending);
        alarmManager.cancel(disconnectPending);
        
        // Remove from storage
        removeSchedule(scheduleId);
        
        Log.d(TAG, "Cancelled schedule: " + scheduleId);
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
