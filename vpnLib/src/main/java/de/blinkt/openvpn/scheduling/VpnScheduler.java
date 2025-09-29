package de.blinkt.openvpn.scheduling;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.Calendar;
import java.util.List;

/**
 * Main interface for VPN scheduling functionality
 */
public class VpnScheduler {
    private static final String TAG = "VpnScheduler";
    
    private Context context;
    private VpnSchedulerService schedulerService;
    
    public VpnScheduler(Context context) {
        this.context = context;
    }
    
    /**
     * Schedule a VPN connection at a specific UTC time
     * @param config OpenVPN configuration string
     * @param name Profile name
     * @param username Authentication username
     * @param password Authentication password
     * @param connectTimeUTC Connect time in UTC milliseconds
     * @param disconnectTimeUTC Disconnect time in UTC milliseconds (0 for manual disconnect)
     * @param bypassPackages List of package names to bypass VPN
     * @return Schedule ID
     */
    public String scheduleVpn(String config, String name, String username, String password,
                              long connectTimeUTC, long disconnectTimeUTC, List<String> bypassPackages) {
        
        VpnSchedule schedule = new VpnSchedule(config, name, username, password, connectTimeUTC, disconnectTimeUTC);
        
        if (bypassPackages != null && !bypassPackages.isEmpty()) {
            schedule.setBypassPackages(new com.google.gson.Gson().toJson(bypassPackages));
        }
        
        startSchedulerService();
        scheduleVpnInternal(schedule);
        
        Log.d(TAG, "Scheduled VPN: " + schedule.getId() + " for " + connectTimeUTC);
        return schedule.getId();
    }
    
    
    /**
     * Cancel a scheduled VPN
     * @param scheduleId Schedule ID to cancel
     */
    public void cancelSchedule(String scheduleId) {
        startSchedulerService();
        cancelScheduleInternal(scheduleId);
        Log.d(TAG, "Cancelled schedule: " + scheduleId);
    }
    
    /**
     * Get all scheduled VPNs
     * @return List of all schedules
     */
    public List<VpnSchedule> getAllSchedules() {
        startSchedulerService();
        return getAllSchedulesInternal();
    }
    
    private void startSchedulerService() {
        Intent serviceIntent = new Intent(context, VpnSchedulerService.class);
        context.startService(serviceIntent);
    }
    
    private void scheduleVpnInternal(VpnSchedule schedule) {
        // Start the service and pass the schedule
        Intent serviceIntent = new Intent(context, VpnSchedulerService.class);
        serviceIntent.putExtra("action", "schedule");
        serviceIntent.putExtra("schedule", schedule);
        context.startService(serviceIntent);
    }
    
    private void cancelScheduleInternal(String scheduleId) {
        Intent serviceIntent = new Intent(context, VpnSchedulerService.class);
        serviceIntent.putExtra("action", "cancel");
        serviceIntent.putExtra("scheduleId", scheduleId);
        context.startService(serviceIntent);
    }
    
    private List<VpnSchedule> getAllSchedulesInternal() {
        // For now, return empty list - this would need proper service binding
        return new java.util.ArrayList<>();
    }
    
    private void updateScheduleInternal(VpnSchedule schedule) {
        Intent serviceIntent = new Intent(context, VpnSchedulerService.class);
        serviceIntent.putExtra("action", "update");
        serviceIntent.putExtra("schedule", schedule);
        context.startService(serviceIntent);
    }
}
