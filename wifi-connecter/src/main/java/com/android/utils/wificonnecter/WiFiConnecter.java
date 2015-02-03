package com.android.utils.wificonnecter;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.util.Log;

import com.farproc.wifi.connecter.Wifi;

import java.util.List;

/**
 * Created by BSDC-ZLS on 2014/10/29.
 */
public class WiFiConnecter {

    // Combo scans can take 5-6s to complete
    private static final int WIFI_RESCAN_INTERVAL_MS = 5 * 1000;
    private static final int MAX_TRY_COUNT = 3;
    private static final String TAG = WiFiConnecter.class.getSimpleName();

    private Context mContext;
    private WifiManager mWifiManager;

    private final IntentFilter mFilter;
    private BroadcastReceiver mReceiver;
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

        isRegistered = true;
        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleEvent(context, intent);
            }
        };

        Log.d(TAG, "registerReceiver:" + mReceiver.toString());
        mContext.registerReceiver(mReceiver, mFilter);

        if (listener != null) {
            listener.onStarted(ssid);
        }

        isActiveScan = true;
        mScanner.forceScan();
    }

    private void handleEvent(Context context, Intent intent) {
//        String action = ;
//        Log.d("WiFiConnecter","action:"+action);
        //TODO zls:Error receiving broadcast Intent { act=android.net.wifi.SCAN_RESULTS flg=0x4000010 }
        if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction()) && isActiveScan) {
            isActiveScan = false;
            List<ScanResult> results = mWifiManager.getScanResults();
            Log.d(TAG, "scan results:" + results.toString());
            for (ScanResult result : results) {
                if (mSsid.equals(result.SSID)) {
                    //            mScanner.pause();
                    Log.d(TAG, "find result:" + result);
                    //https://code.google.com/p/android-wifi-connecter/
//                  Loger.d("result.SSID=" +result.SSID+"  newNetwork:"+newNetwork);
                    boolean connectResult = connect(context, result, mPassword);
                    Log.d(TAG, "connect result:" + connectResult);
                    if (!connectResult) {
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
            Log.d(TAG, "NETWORK_STATE_CHANGED_ACTION");
            Log.d(TAG, "NetworkInfo mState:" + mInfo.getState());
            if (mInfo.isConnected() && mWifiInfo != null && mWifiInfo.getSSID() != null &&
                    //nexus 5 have quote,sumsang don't have quote
                    (mWifiInfo.getSSID().equals(mSsid) ||
                            mWifiInfo.getSSID().equals(Wifi.convertToQuotedString(mSsid)))) {
                if (mListener != null) {
//                    Loger.d("success");
                    mListener.onSuccess(mWifiInfo);
                    mListener.onFinished(true);
                }
                onPause();
            }
        }
    }

    /**
     * connect wifi
     *
     * @param context
     * @param mScanResult
     * @param mPassword
     * @return
     */

    public boolean connect(Context context, ScanResult mScanResult, String mPassword) {
        boolean connResult;

        String security = Wifi.ConfigSec.getScanResultSecurity(mScanResult);
        WifiConfiguration config = Wifi.getWifiConfiguration(mWifiManager, mScanResult, security);

        if (config == null) {
            Log.d(TAG, "start connect new network");
            connResult = newNetWork(context, mScanResult, mPassword);
        } else {
            final boolean isCurrentNetwork_ConfigurationStatus = config.status == WifiConfiguration.Status.CURRENT;
            final WifiInfo info = mWifiManager.getConnectionInfo();
            final boolean isCurrentNetwork_WifiInfo = info != null
                    && android.text.TextUtils.equals(info.getSSID(), mScanResult.SSID)
                    && android.text.TextUtils.equals(info.getBSSID(), mScanResult.BSSID);
            if (isCurrentNetwork_ConfigurationStatus || isCurrentNetwork_WifiInfo) {
                connResult = true;
            } else {
                Log.d(TAG, "start connect configure network");
                connResult = configuredNetwork(context, config, security, mPassword);
            }
        }
        return connResult;
    }

    /**
     * Configure a network, and connect to it.
     *
     * @param context
     * @param mScanResult
     * @param mPassword
     * @return
     */
    private boolean newNetWork(Context context, ScanResult mScanResult, String mPassword) {
        boolean connResult;
        int mNumOpenNetworksKept = Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.WIFI_NUM_OPEN_NETWORKS_KEPT, 10);
        String mScanResultSecurity = Wifi.ConfigSec.getScanResultSecurity(mScanResult);
        boolean mIsOpenNetwork = Wifi.ConfigSec.isOpenNetwork(mScanResultSecurity);
        String password = mIsOpenNetwork ? null : mPassword;
        connResult = Wifi.connectToNewNetwork(context, mWifiManager, mScanResult, password, mNumOpenNetworksKept);
        return connResult;
    }

    /**
     * Connect to a configured network.
     *
     * @param context
     * @return
     */
    private boolean configuredNetwork(Context context, WifiConfiguration config) {
        boolean connResult = false;
        if (config != null) {
            connResult = Wifi.connectToConfiguredNetwork(context, mWifiManager, config, false);
        }
        return connResult;
    }

    /**
     * Connect to a configured network.
     *
     * @param context
     * @param mPassword optional
     * @param security
     * @param config
     * @return
     */
    private boolean configuredNetwork(Context context, WifiConfiguration config, String security, String mPassword) {
        //optional
        Wifi.ConfigSec.setupSecurity(config, security, mPassword);
        return configuredNetwork(context, config);
    }

    public void onPause() {
        if (isRegistered) {
            Log.d(TAG, "unregisterReceiver:" + mReceiver.toString());
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
            Log.d(TAG, "mRetry:" + mRetry);
            if (mRetry < MAX_TRY_COUNT) {
                mRetry++;
                isActiveScan = true;
                //1.open Wifi
                if (!mWifiManager.isWifiEnabled()) {
                    mWifiManager.setWifiEnabled(true);
                }
                //TODO startScan return false?
                boolean startScan = mWifiManager.startScan();
                Log.d(TAG, "startScan:" + startScan);
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
         *
         * @param ssid
         */
        public void onStarted(String ssid);

        /**
         * The operation succeeded
         *
         * @param info
         */
        public void onSuccess(WifiInfo info);

        /**
         * The operation failed
         */
        public void onFailure();

        /**
         * The operation finished
         *
         * @param isSuccess
         */
        public void onFinished(boolean isSuccess);
    }

}
