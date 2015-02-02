package com.android.utils.wificonnecter;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import com.farproc.wifi.connecter.Wifi;

import java.util.List;

/**
 * Created by BSDC-ZLS on 2014/10/29.
 */
public class WiFiConnecter {

    // Combo scans can take 5-6s to complete
    private static final int WIFI_RESCAN_INTERVAL_MS = 10 * 1000;

    static final int SECURITY_NONE = 0;
    static final int SECURITY_WEP = 1;
    static final int SECURITY_PSK = 2;
    static final int SECURITY_EAP = 3;
    private static final int MAX_TRY_COUNT = 3;

    private Context mContext;
    private WifiManager mWifiManager;

    private final IntentFilter mFilter;
    private final BroadcastReceiver mReceiver;
    private final Scanner mScanner;
    private ActionListener mListener;
    private String mSsid;
    private String mPassword;

    private boolean isRegistered;
    private boolean isActiveScan;

    public WiFiConnecter(Context context) {
        this.mContext = context;
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);

        mFilter = new IntentFilter();
        mFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        mFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        mFilter.addAction(WifiManager.NETWORK_IDS_CHANGED_ACTION);
        mFilter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        mFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);

        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleEvent(context, intent);
            }
        };

        context.registerReceiver(mReceiver, mFilter);
        isRegistered = true;
        mScanner = new Scanner();
    }


    /**
     * Connect to a WiFi with the given ssid and password
     *
     * @param ssid
     * @param password
     * @param listener : for callbacks on start or success or failure. Can be null.
     */
    public void connect(String ssid, String password, ActionListener listener) {
        this.mListener = listener;
        this.mSsid = ssid;
        this.mPassword = password;

        if (listener != null) {
            listener.onStarted(ssid);
        }

        WifiInfo info = mWifiManager.getConnectionInfo();
        if (info != null && mSsid.equalsIgnoreCase(info.getSSID())) {
            if (listener != null) {
                listener.onSuccess(info);
                listener.onFinished(true);
            }
            return;
        }

        isActiveScan=true;
        mScanner.forceScan();
    }

    private void  handleEvent(Context context, Intent intent) {
//        String action = ;
//        Log.d("WiFiConnecter","action:"+action);
        //TODO zls:Error receiving broadcast Intent { act=android.net.wifi.SCAN_RESULTS flg=0x4000010 }
        if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction()) && isActiveScan) {
            isActiveScan=false;

            List<ScanResult> results = mWifiManager.getScanResults();
            Log.d("WiFiConnecter","results:"+results.size());
            for (ScanResult result : results) {
                if (mSsid.equalsIgnoreCase(result.SSID)) {
                    //            mScanner.pause();

//                    final WifiConfiguration config = Wifi.getWifiConfiguration(mWifiManager, mScanResult, mScanResultSecurity);
                    boolean connResult = false;
//                    if (config != null) {
                        connResult = Wifi.connectToConfiguredNetwork(null, mWifiManager, null, false);
//                    }
                    if (!connResult) {
//                        Toast.makeText(mFloating, R.string.toastFailed, Toast.LENGTH_LONG).show();
                    }
                    boolean newNetwork = WiFi.connectToNewNetwork(mWifiManager, result, mPassword);
//                    Loger.d("result.SSID=" +result.SSID+"  newNetwork:"+newNetwork);
                    if (!newNetwork) {
                        if (mListener != null) {
                            mListener.onFailure();
                            mListener.onFinished(false);
                        }
                        onPause();
                    }
                    break;
                }
            }

        } else if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(intent.getAction())) {
            //Loger.d("NETWORK_STATE_CHANGED_ACTION");
            NetworkInfo mInfo = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
            WifiInfo mWifiInfo = mWifiManager.getConnectionInfo();
            //Loger.d("mInfo:"+mInfo.toString());
            if (mInfo.isConnected() && mWifiInfo != null && mWifiInfo.getSSID() != null &&
                    //很坑，居然返回带双引号的字符
                    mWifiInfo.getSSID().equalsIgnoreCase("\""+mSsid+"\"")) {
                if (mListener != null) {
//                    Loger.d("success");
                    mListener.onSuccess(mWifiInfo);
                    mListener.onFinished(true);
                }
                onPause();
            }
        }
    }

    public void onPause() {
        if (isRegistered) {
            mContext.unregisterReceiver(mReceiver);
            isRegistered = false;
        }
        mScanner.pause();
    }

    public void onResume() {
        if (!isRegistered) {
            mContext.registerReceiver(mReceiver, mFilter);
            isRegistered = true;
        }
        mScanner.resume();
    }

    @SuppressLint("HandlerLeak")
    private class Scanner extends Handler {
        private int mRetry = 0;

        void resume() {
            if (!hasMessages(0)) {
                sendEmptyMessage(0);
            }
        }

        void forceScan() {
            removeMessages(0);
            sendEmptyMessage(0);
        }

        void pause() {
            mRetry = 0;
            isActiveScan = false;
            removeMessages(0);
        }

        @Override
        public void handleMessage(Message message) {
            if (mRetry < MAX_TRY_COUNT) {
                mRetry++;
                isActiveScan = true;
                //1.open Wifi
                if (!mWifiManager.isWifiEnabled()) {
                    mWifiManager.setWifiEnabled(true);
                }
                //TODO startScan return false?
                boolean startScan = mWifiManager.startScan();
                // exe scan fail(bind mechanism)
                if (!startScan) {
                    if (mListener != null) {
                        mListener.onFailure();
                        mListener.onFinished(true);
                    }
                    onPause();
                    return;
                }
                //mRetry>=3
            } else {
                mRetry = 0;
                isActiveScan = false;
                if (mListener != null) {
                    mListener.onFailure();
                    mListener.onFinished(true);
                }
                onPause();
                return;
            }
            sendEmptyMessageDelayed(0, WIFI_RESCAN_INTERVAL_MS);
        }
    }

    public interface ActionListener {

        /**
         * The operation started
         * @param ssid
         */
        public void onStarted(String ssid);

        /**
         * The operation succeeded
         * @param info
         */
        public void onSuccess(WifiInfo info);

        /**
         * The operation failed
         */
        public void onFailure();

        /**
         * The operation finished
         * @param b
         */
        public void onFinished(boolean b);
    }

}
