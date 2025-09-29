package de.blinkt.openvpn;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

import de.blinkt.openvpn.core.OpenVPNService;
import de.blinkt.openvpn.core.OpenVPNThread;
import de.blinkt.openvpn.core.VpnStatus;
import de.blinkt.openvpn.scheduling.VpnScheduler;
import de.blinkt.openvpn.scheduling.VpnSchedule;

public class VPNHelper extends Activity {
    public Activity activity;
    public static OnVPNStatusChangeListener listener;
    private static String config;
    private static boolean vpnStart;
    private static Intent profileIntent;
    private static String username;
    private static String password;
    private static String name;
    private static List<String> bypassPackages;

    public JSONObject status = new JSONObject();
    private VpnScheduler vpnScheduler;

    public boolean isConnected(){
        return vpnStart;
    }

    public VPNHelper(Activity activity) {
        this.activity = activity;
        VPNHelper.vpnStart = false;
        VpnStatus.initLogCache(activity.getCacheDir());
        this.vpnScheduler = new VpnScheduler(activity);
    }

    public void setOnVPNStatusChangeListener(OnVPNStatusChangeListener listener) {
        VPNHelper.listener = listener;
        LocalBroadcastManager.getInstance(activity).registerReceiver(broadcastReceiver, new IntentFilter("connectionState"));
    }

    public void startVPN() {
        if (!vpnStart) connect();
    }


    public void startVPN(String config, String username, String password, String name, List<String> bypass) {
        startVPN(config, username, password, name, bypass, 0, 0); // Default to immediate
    }

    /**
     * Single function to start VPN - immediate or scheduled
     * @param config OpenVPN configuration string
     * @param username Authentication username
     * @param password Authentication password
     * @param name Profile name
     * @param bypassPackages List of package names to bypass VPN
     * @param startTimeUTC Start time in UTC milliseconds (0 for immediate connection)
     * @param endTimeUTC End time in UTC milliseconds (0 for manual disconnect)
     * @return Schedule ID if scheduled, null if immediate
     */
    public String startVPN(String config, String username, String password, String name, List<String> bypassPackages, long startTimeUTC, long endTimeUTC) {
        VPNHelper.config = config;
        VPNHelper.profileIntent = VpnService.prepare(activity);
        VPNHelper.username = username;
        VPNHelper.password = password;
        VPNHelper.name = name;
        VPNHelper.bypassPackages = bypassPackages;

        // If no scheduling time provided, use original immediate connection
        if (startTimeUTC <= 0) {
            if (profileIntent != null) {
                activity.startActivityForResult(VPNHelper.profileIntent, 1);
            } else {
                startVPN();
            }
            return null; // Immediate connection
        }
        
        // Schedule VPN
        return scheduleVpn(config, name, username, password, startTimeUTC, endTimeUTC, bypassPackages);
    }


    /**
     * Start VPN with optional scheduling (today's time)
     * @param config OpenVPN configuration string
     * @param username Authentication username
     * @param password Authentication password
     * @param name Profile name
     * @param bypassPackages List of package names to bypass VPN
     * @param connectHour Connect hour (0-23, -1 for immediate)
     * @param connectMinute Connect minute (0-59)
     * @param disconnectHour Disconnect hour (0-23, -1 for manual disconnect)
     * @param disconnectMinute Disconnect minute (0-59)
     * @return Schedule ID if scheduled, null if immediate connection
     */
    public String startVPN(String config, String username, String password, String name, 
                          List<String> bypassPackages, int connectHour, int connectMinute, 
                          int disconnectHour, int disconnectMinute) {
        
        // If no scheduling time provided, use original immediate connection
        if (connectHour < 0) {
            startVPN(config, username, password, name, bypassPackages);
            return null;
        }
        
        // Calculate UTC times for today
        java.util.Calendar today = java.util.Calendar.getInstance();
        java.util.Calendar connectTime = java.util.Calendar.getInstance();
        connectTime.set(java.util.Calendar.HOUR_OF_DAY, connectHour);
        connectTime.set(java.util.Calendar.MINUTE, connectMinute);
        connectTime.set(java.util.Calendar.SECOND, 0);
        connectTime.set(java.util.Calendar.MILLISECOND, 0);
        
        java.util.Calendar disconnectTime = java.util.Calendar.getInstance();
        disconnectTime.set(java.util.Calendar.HOUR_OF_DAY, disconnectHour);
        disconnectTime.set(java.util.Calendar.MINUTE, disconnectMinute);
        disconnectTime.set(java.util.Calendar.SECOND, 0);
        disconnectTime.set(java.util.Calendar.MILLISECOND, 0);
        
        // If disconnect time is before connect time, assume next day
        if (disconnectTime.getTimeInMillis() <= connectTime.getTimeInMillis()) {
            disconnectTime.add(java.util.Calendar.DAY_OF_MONTH, 1);
        }
        
        // Schedule VPN for today
        return scheduleVpn(config, name, username, password, 
                         connectTime.getTimeInMillis(), 
                         disconnectTime.getTimeInMillis(), 
                         bypassPackages);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.launchvpn);
        LocalBroadcastManager.getInstance(activity).registerReceiver(broadcastReceiver, new IntentFilter("connectionState"));
        startVPN();
    }

    public void stopVPN() {
        OpenVPNThread.stop();
        
        // Clear all previous schedules when disconnecting
        if (vpnScheduler != null) {
            List<VpnSchedule> schedules = vpnScheduler.getAllSchedules();
            for (VpnSchedule schedule : schedules) {
                vpnScheduler.cancelSchedule(schedule.getId());
            }
            Log.d("VPN", "Cleared all previous schedules");
        }
    }


    // ========== INTERNAL SCHEDULING METHODS ==========

    /**
     * Internal method to schedule VPN
     */
    private String scheduleVpn(String config, String name, String username, String password,
                             long startTimeUTC, long endTimeUTC, List<String> bypassPackages) {
        return vpnScheduler.scheduleVpn(config, name, username, password, startTimeUTC, endTimeUTC, bypassPackages);
    }

    private void connect() {
        try {
            OpenVpnApi.startVpn(activity, config,name, username, password, bypassPackages);
            vpnStart = true;
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void setStage(String stage) {
        String output = stage;
        switch (stage.toUpperCase()) {
            case "CONNECTED":
                output = "connected";
                vpnStart = true;
                break;
            case "DISCONNECTED":
                output = "disconnected";
                vpnStart = false;
                OpenVPNService.setDefaultStatus();
                break;
            case "WAIT":
                output = "wait_connection";
                break;
            case "AUTH":
                output = "authenticating";
                break;
            case "RECONNECTING":
                output = "reconnect";
                break;
            case "NONETWORK":
                output = "no_connection";
                break;
            case "CONNECTING":
                output = "connecting";
                break;
            case "PREPARE":
                output = "prepare";
                break;
            case "DENIED":
                output = "denied";
                break;
            case "ERROR":
                output = "error";
                break;
        }
        if (listener != null) listener.onVPNStatusChanged(output);
    }


    BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            try {
                if (intent.getStringExtra("state") != null) {
                    setStage(intent.getStringExtra("state"));
                }
                String duration = intent.getStringExtra("duration");
                String lastPacketReceive = intent.getStringExtra("lastPacketReceive");
                String byteIn = intent.getStringExtra("byteIn");
                String byteOut = intent.getStringExtra("byteOut");

                if (duration == null) duration = "00:00:00";
                if (lastPacketReceive == null) lastPacketReceive = "0";
                if (byteIn == null) byteIn = " ";
                if (byteOut == null) byteOut = " ";
                JSONObject jsonObject = new JSONObject();

                try {
                    jsonObject.put("connected_on", duration);
                    jsonObject.put("last_packet_receive", lastPacketReceive);
                    jsonObject.put("byte_in", byteIn);
                    jsonObject.put("byte_out", byteOut);

                    status = jsonObject;
                } catch (JSONException e) {
                    e.printStackTrace();
                }

                VPNHelper.listener.onConnectionStatusChanged(duration, lastPacketReceive, byteIn, byteOut);
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
    };

    @Override
    public void onDetachedFromWindow() {
        LocalBroadcastManager.getInstance(activity).unregisterReceiver(broadcastReceiver);
        super.onDetachedFromWindow();
    }

    @Override
    public void onAttachedToWindow() {
        LocalBroadcastManager.getInstance(activity).registerReceiver(broadcastReceiver, new IntentFilter("connectionState"));
        super.onAttachedToWindow();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 1) {
            if (resultCode == RESULT_OK) {
                startVPN();
            } else {
                VPNHelper.listener.onVPNStatusChanged("denied");
            }
        }
    }
}