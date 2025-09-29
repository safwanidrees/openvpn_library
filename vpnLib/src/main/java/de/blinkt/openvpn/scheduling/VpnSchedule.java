package de.blinkt.openvpn.scheduling;

import java.io.Serializable;
import java.util.Calendar;
import java.util.Date;
import java.util.UUID;

/**
 * Data model for VPN scheduling
 */
public class VpnSchedule implements Serializable {
    private String id;
    private String config;
    private String name;
    private String username;
    private String password;
    private long connectTimeUTC;
    private long disconnectTimeUTC;
    private boolean isActive;
    private boolean isRecurring;
    private int recurringDays; // Bitmask for days of week (1=Sunday, 2=Monday, etc.)
    private String bypassPackages; // JSON string of package names
    
    public VpnSchedule() {
        this.id = UUID.randomUUID().toString();
        this.isActive = true;
        this.isRecurring = false;
        this.recurringDays = 0;
    }
    
    public VpnSchedule(String config, String name, String username, String password, 
                      long connectTimeUTC, long disconnectTimeUTC) {
        this();
        this.config = config;
        this.name = name;
        this.username = username;
        this.password = password;
        this.connectTimeUTC = connectTimeUTC;
        this.disconnectTimeUTC = disconnectTimeUTC;
    }
    
    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getConfig() { return config; }
    public void setConfig(String config) { this.config = config; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    
    public long getConnectTimeUTC() { return connectTimeUTC; }
    public void setConnectTimeUTC(long connectTimeUTC) { this.connectTimeUTC = connectTimeUTC; }
    
    public long getDisconnectTimeUTC() { return disconnectTimeUTC; }
    public void setDisconnectTimeUTC(long disconnectTimeUTC) { this.disconnectTimeUTC = disconnectTimeUTC; }
    
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
    
    public boolean isRecurring() { return isRecurring; }
    public void setRecurring(boolean recurring) { isRecurring = recurring; }
    
    public int getRecurringDays() { return recurringDays; }
    public void setRecurringDays(int recurringDays) { this.recurringDays = recurringDays; }
    
    public String getBypassPackages() { return bypassPackages; }
    public void setBypassPackages(String bypassPackages) { this.bypassPackages = bypassPackages; }
    
    /**
     * Get the next connect time for recurring schedules
     * Since app always sends UTC time, we work with UTC timestamps
     */
    public long getNextConnectTime() {
        if (!isRecurring) {
            return connectTimeUTC;
        }
        
        // For recurring schedules, calculate next occurrence
        long currentTime = System.currentTimeMillis();
        
        // If connect time is in the future, use it
        if (connectTimeUTC > currentTime) {
            return connectTimeUTC;
        }
        
        // Calculate next occurrence based on recurring days
        Calendar connect = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        connect.setTimeInMillis(connectTimeUTC);
        
        Calendar now = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        now.setTimeInMillis(currentTime);
        
        // Find next occurrence within the next 7 days
        for (int i = 0; i < 7; i++) {
            Calendar next = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            next.setTimeInMillis(connectTimeUTC);
            next.add(Calendar.DAY_OF_YEAR, i);
            
            int dayOfWeek = next.get(Calendar.DAY_OF_WEEK);
            int dayMask = 1 << (dayOfWeek - 1);
            
            if ((recurringDays & dayMask) != 0 && next.getTimeInMillis() > currentTime) {
                return next.getTimeInMillis();
            }
        }
        
        // If no valid day found in next 7 days, return original time
        return connectTimeUTC;
    }
    
    /**
     * Check if schedule should trigger at given time
     * Since app always sends UTC time, we work directly with UTC timestamps
     */
    public boolean shouldTriggerAt(long currentTimeUTC) {
        if (!isActive) return false;
        
        // Since both currentTimeUTC and connectTimeUTC are in UTC, no timezone conversion needed
        if (isRecurring) {
            // For recurring schedules, check if current day matches recurring days
            // Convert UTC time to day of week for recurring logic
            Calendar current = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            current.setTimeInMillis(currentTimeUTC);
            
            Calendar connect = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            connect.setTimeInMillis(connectTimeUTC);
            
            // Check if current day matches recurring days
            int currentDayOfWeek = current.get(Calendar.DAY_OF_WEEK);
            int dayMask = 1 << (currentDayOfWeek - 1);
            if ((recurringDays & dayMask) == 0) return false;
            
            // Check if current time is within connect time window (within 1 minute)
            long timeDiff = Math.abs(currentTimeUTC - connectTimeUTC);
            return timeDiff <= 60000; // 1 minute tolerance
        } else {
            // One-time schedule - direct UTC comparison
            long timeDiff = Math.abs(currentTimeUTC - connectTimeUTC);
            return timeDiff <= 60000; // 1 minute tolerance
        }
    }
    
    /**
     * Check if schedule should disconnect at given time
     */
    public boolean shouldDisconnectAt(long currentTimeUTC) {
        if (!isActive) return false;
        return currentTimeUTC >= disconnectTimeUTC;
    }
    
}
