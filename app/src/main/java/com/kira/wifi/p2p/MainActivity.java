package com.kira.wifi.p2p;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.RotateAnimation;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    public static final String TCP_CLIENT_ACTION = "tcp.client.action";
    public static final String EXTRA_IP_ADDR = "extra.ip.addr";
    private static final String TAG = "MainActivity";
    private static final int CHOOSE_FILE_RESULT_CODE = 20;
    private static final int PERMISSION_REQUEST_CODE = 1;
    private static final int TRANSFER_PORT = 8988;

    private WifiP2pManager mP2pManager;
    private WifiP2pManager.Channel mChannel;
    private BroadcastReceiver mReceiver;
    private ListView mPeerListView;
    private WiFiPeerListAdapter mWiFiPeerListAdapter;
    private List<WifiP2pDevice> mPeers;
    private WifiP2pManager.PeerListListener myPeerListListener;
    private WifiP2pManager.ConnectionInfoListener mConnectionInfoListener;
    private WifiP2pManager.GroupInfoListener mGroupInfoListener;
    private FloatingActionButton mScanFab;
    private TextView mRoleText;
    private TextView mGoAddrText;
    private TextView mDevNameText;
    private TextView mNetworkNameText;
    private RotateAnimation mAnimation;
    private boolean mScanState = false;
    private Button mGoSwitchBtn;
    private Button mBrowseBtn;
    private List<String> mClientList;
    private Spinner mClientListSpin;
    private ArrayAdapter<String> mClientListAdapter;
    private WifiP2pInfo mP2pInfo;
    private BroadcastReceiver mMainReceiver;
    private boolean mGroupOwner;
    private String mClientAddr;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        checkPermission();

        // Open WiFi if disabled
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null && !wifiManager.isWifiEnabled()) {
            wifiManager.setWifiEnabled(true);
        }

        // Check location service
        LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        boolean locationOk = locationManager != null && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        if (!locationOk) {
            Toast.makeText(this, R.string.turn_on_location, Toast.LENGTH_LONG).show();
            Intent intent = new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            startActivity(intent);
        }

        // Init views
        mScanFab = findViewById(R.id.scan);
        mRoleText = findViewById(R.id.role);
        mGoAddrText = findViewById(R.id.go_addr);
        mGoSwitchBtn = findViewById(R.id.go_switch);
        mBrowseBtn = findViewById(R.id.browse);
        mPeerListView = findViewById(R.id.peer_list);
        mDevNameText = findViewById(R.id.dev_name);
        mNetworkNameText = findViewById(R.id.net_name);
        mClientListSpin = findViewById(R.id.client_list);
        mPeers = new ArrayList<>();
        mWiFiPeerListAdapter = new WiFiPeerListAdapter(getApplicationContext(), mPeers);
        mClientList = new ArrayList<>();
        mClientListAdapter = new ArrayAdapter<>(getApplicationContext(),
                android.R.layout.simple_list_item_single_choice, mClientList);
        mClientListSpin.setAdapter(mClientListAdapter);

        mP2pManager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        mChannel = mP2pManager.initialize(getApplicationContext(), getMainLooper(), null);

        resetDisplayUI();

        // Group create/remove button
        mGoSwitchBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mGoSwitchBtn.getText().equals(getString(R.string.go_create))) {
                    createGroup();
                } else {
                    removeGroup();
                }
            }
        });

        // Select Files button — opens file manager, sends immediately after selection
        mBrowseBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("image/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES,
                        new String[]{"image/*", "video/*", "application/*"});
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(intent, CHOOSE_FILE_RESULT_CODE);
            }
        });

        mPeerListView.setAdapter(mWiFiPeerListAdapter);

        myPeerListListener = new WifiP2pManager.PeerListListener() {
            @Override
            public void onPeersAvailable(WifiP2pDeviceList peerList) {
                Collection<WifiP2pDevice> refreshedPeers = peerList.getDeviceList();
                if (!refreshedPeers.equals(mPeers)) {
                    mPeers.clear();
                    mPeers.addAll(refreshedPeers);
                    updateP2pListView();
                }
                if (mPeers.size() == 0) {
                    Log.d(TAG, "No devices found");
                }
            }
        };

        mConnectionInfoListener = new WifiP2pManager.ConnectionInfoListener() {
            @Override
            public void onConnectionInfoAvailable(WifiP2pInfo info) {
                mP2pInfo = info;
                if (info.groupFormed && info.isGroupOwner) {
                    setDisplayUI("GO");
                    mGroupOwner = true;
                } else if (info.groupFormed) {
                    mGroupOwner = false;
                    setDisplayUI("GC");
                }

                if (info.groupFormed) {
                    Intent recvIntent = new Intent(MainActivity.this,
                            WiFiDirectReceiveService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(recvIntent);
                    } else {
                        startService(recvIntent);
                    }
                    if (info.groupOwnerAddress != null) {
                        mGoAddrText.setText(getString(R.string.go_addr)
                                + info.groupOwnerAddress.getHostAddress());
                    }
                } else {
                    // Group dissolved — stop receive service
                    stopService(new Intent(MainActivity.this,
                            WiFiDirectReceiveService.class));
                    resetDisplayUI();
                }
            }
        };

        mGroupInfoListener = new WifiP2pManager.GroupInfoListener() {
            @Override
            public void onGroupInfoAvailable(WifiP2pGroup group) {
                mNetworkNameText.setText(getString(R.string.net_name) + group.getNetworkName());
            }
        };

        mClientListSpin.setOnItemSelectedListener(new Spinner.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!mClientList.isEmpty()) {
                    mClientAddr = mClientList.get(position);
                    Log.d(TAG, "onItemSelected: address " + mClientAddr);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // Scanning animation
        mAnimation = new RotateAnimation(0, 359, RotateAnimation.RELATIVE_TO_SELF,
                0.5f, RotateAnimation.RELATIVE_TO_SELF, 0.5f);
        mAnimation.setDuration(100);
        mAnimation.setRepeatCount(Animation.INFINITE);
        mAnimation.setRepeatMode(Animation.RESTART);

        mScanFab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mScanState) {
                    startDiscovery();
                } else {
                    stopDiscovery();
                }
            }
        });

        mPeerListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                WifiP2pDevice p2pDevice = mPeers.get(position);
                Log.d(TAG, "Click Device: " + p2pDevice.deviceName + " " + p2pDevice.deviceAddress);
                if (p2pDevice.status == WifiP2pDevice.AVAILABLE) {
                    connect(p2pDevice);
                } else if (p2pDevice.status == WifiP2pDevice.INVITED) {
                    cancelConnect(p2pDevice);
                } else if (p2pDevice.status == WifiP2pDevice.CONNECTED) {
                    if (!mGroupOwner) {
                        removeGroup();
                    }
                }
            }
        });

        // Receiver for TCP client notifications from ReceiveService
        mMainReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (TCP_CLIENT_ACTION.equals(action)) {
                    String address = intent.getStringExtra(EXTRA_IP_ADDR);
                    if (address != null && !mClientList.contains(address)) {
                        mClientList.add(address);
                        updateClientListSpin();
                    }
                }
            }
        };

        // Register WiFi Direct broadcast receiver
        IntentFilter p2pFilter = new IntentFilter();
        p2pFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        p2pFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        p2pFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        p2pFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        mReceiver = new WiFiDirectBroadcastReceiver(mP2pManager, mChannel, this,
                myPeerListListener, mConnectionInfoListener, mGroupInfoListener);
        registerReceiver(mReceiver, p2pFilter);

        IntentFilter filter = new IntentFilter();
        filter.addAction(TCP_CLIENT_ACTION);
        registerReceiver(mMainReceiver, filter);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            return;
        }
        if (requestCode != CHOOSE_FILE_RESULT_CODE) {
            return;
        }
        if (mP2pInfo == null) {
            Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show();
            return;
        }

        // Determine target address
        String addrStr;
        if (!mGroupOwner) {
            if (mP2pInfo.groupOwnerAddress != null) {
                addrStr = mP2pInfo.groupOwnerAddress.getHostAddress();
            } else {
                Toast.makeText(this, R.string.no_target_device, Toast.LENGTH_SHORT).show();
                return;
            }
        } else {
            if (mClientAddr == null || mClientAddr.isEmpty()) {
                Toast.makeText(this, R.string.no_target_device, Toast.LENGTH_SHORT).show();
                return;
            }
            addrStr = mClientAddr;
        }

        Log.d(TAG, "onActivityResult: target " + addrStr);

        // Collect selected file URIs and send immediately
        Uri uri = data.getData();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2 && uri == null) {
            ClipData clipData = data.getClipData();
            if (clipData != null) {
                int count = clipData.getItemCount();
                Toast.makeText(this, getString(R.string.sending_files, count), Toast.LENGTH_SHORT).show();
                for (int i = 0; i < count; i++) {
                    sendFile(addrStr, clipData.getItemAt(i).getUri().toString());
                }
            }
        } else if (uri != null) {
            Toast.makeText(this, R.string.sending_file, Toast.LENGTH_SHORT).show();
            sendFile(addrStr, uri.toString());
        }
    }

    private void sendFile(String addrStr, String uriStr) {
        Intent serviceIntent = new Intent(this, WiFiDirectSendService.class);
        serviceIntent.setAction(WiFiDirectSendService.ACTION_SEND_FILE);
        serviceIntent.putExtra(WiFiDirectSendService.EXTRAS_FILE_PATH, uriStr);
        serviceIntent.putExtra(WiFiDirectSendService.EXTRAS_ADDRESS, addrStr);
        serviceIntent.putExtra(WiFiDirectSendService.EXTRAS_PORT, TRANSFER_PORT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void checkPermission() {
        ArrayList<String> permissionList = new ArrayList<>();
        permissionList.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        permissionList.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissionList.add(Manifest.permission.ACCESS_WIFI_STATE);
        permissionList.add(Manifest.permission.CHANGE_WIFI_STATE);
        permissionList.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);

        Iterator<String> it = permissionList.iterator();
        while (it.hasNext()) {
            String permission = it.next();
            int hasPermission = ContextCompat.checkSelfPermission(this, permission);
            if (hasPermission == PackageManager.PERMISSION_GRANTED) {
                it.remove();
            }
        }
        if (permissionList.isEmpty()) {
            return;
        }
        String[] permissions = permissionList.toArray(new String[0]);
        ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (!allGranted) {
                Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mReceiver != null) {
            unregisterReceiver(mReceiver);
        }
        if (mMainReceiver != null) {
            unregisterReceiver(mMainReceiver);
        }
    }

    // ---- Peer List Adapter ----

    private class WiFiPeerListAdapter extends BaseAdapter {
        private List<WifiP2pDevice> mPeerDevices;
        private LayoutInflater mInflater;

        WiFiPeerListAdapter(Context context, List<WifiP2pDevice> peerDevices) {
            mInflater = LayoutInflater.from(context);
            mPeerDevices = peerDevices;
        }

        @Override
        public int getCount() {
            return mPeerDevices.size();
        }

        @Override
        public Object getItem(int position) {
            return mPeerDevices.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewGroup vg;
            if (convertView != null) {
                vg = (ViewGroup) convertView;
            } else {
                vg = (ViewGroup) mInflater.inflate(R.layout.p2p_devices, null);
            }

            if (mPeerDevices.isEmpty()) {
                return vg;
            }

            WifiP2pDevice device = mPeerDevices.get(position);
            ((TextView) vg.findViewById(R.id.device_name)).setText(device.deviceName);
            ((TextView) vg.findViewById(R.id.device_address)).setText(device.deviceAddress);

            String statusStr;
            switch (device.status) {
                case WifiP2pDevice.AVAILABLE:
                    statusStr = "AVAILABLE";
                    break;
                case WifiP2pDevice.INVITED:
                    statusStr = "INVITED";
                    break;
                case WifiP2pDevice.CONNECTED:
                    statusStr = "CONNECTED";
                    break;
                case WifiP2pDevice.FAILED:
                    statusStr = "FAILED";
                    break;
                case WifiP2pDevice.UNAVAILABLE:
                default:
                    statusStr = "UNAVAILABLE";
                    break;
            }
            ((TextView) vg.findViewById(R.id.device_status)).setText(statusStr);
            return vg;
        }
    }

    // ---- Group Management ----

    private void createGroup() {
        mP2pManager.createGroup(mChannel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "createGroup onSuccess");
                setDisplayUI("GO");
            }

            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "createGroup onFailure: reason " + reason);
                Toast.makeText(MainActivity.this, R.string.create_group_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void removeGroup() {
        mP2pManager.removeGroup(mChannel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "removeGroup onSuccess");
                stopService(new Intent(MainActivity.this,
                        WiFiDirectReceiveService.class));
                resetDisplayUI();
            }

            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "removeGroup onFailure: reason " + reason);
            }
        });
    }

    private void connect(WifiP2pDevice device) {
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        stopDiscovery();

        mP2pManager.connect(mChannel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "connect onSuccess");
            }

            @Override
            public void onFailure(int reason) {
                Toast.makeText(MainActivity.this, R.string.connect_failed, Toast.LENGTH_SHORT).show();
                Log.e(TAG, "connect onFailure: reason " + reason);
            }
        });
    }

    private void cancelConnect(WifiP2pDevice device) {
        mP2pManager.cancelConnect(mChannel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "cancelConnect onSuccess");
            }

            @Override
            public void onFailure(int reason) {
                Toast.makeText(MainActivity.this, getString(R.string.cancel_connect_failed) + reason,
                        Toast.LENGTH_SHORT).show();
                Log.e(TAG, "cancelConnect onFailure: reason " + reason);
            }
        });
    }

    // ---- Discovery ----

    private void startDiscovery() {
        mP2pManager.discoverPeers(mChannel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                mScanState = true;
                mScanFab.startAnimation(mAnimation);
                Log.d(TAG, "discoverPeers onSuccess");
            }

            @Override
            public void onFailure(int reason) {
                Toast.makeText(MainActivity.this, getString(R.string.discover_failed) + reason,
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void stopDiscovery() {
        mP2pManager.stopPeerDiscovery(mChannel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                mScanState = false;
                mScanFab.clearAnimation();
                Log.d(TAG, "stopPeerDiscovery onSuccess");
            }

            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "stopPeerDiscovery onFailure: " + reason);
            }
        });
    }

    // ---- UI Helpers ----

    public void setP2pEnabled(boolean enabled) {
        if (!enabled) {
            Toast.makeText(this, R.string.p2p_disabled, Toast.LENGTH_SHORT).show();
        }
    }

    @SuppressLint("RestrictedApi")
    private void resetDisplayUI() {
        mGoSwitchBtn.setText(R.string.go_create);
        mRoleText.setText(R.string.role);
        mGoAddrText.setText(R.string.go_addr);
        mNetworkNameText.setText(R.string.net_name);
        mPeers.clear();
        mClientList.clear();
        updateClientListSpin();
        updateP2pListView();
        stopDiscovery();
        mScanFab.clearAnimation();
    }

    @SuppressLint("RestrictedApi")
    private void setDisplayUI(String role) {
        if ("GO".equalsIgnoreCase(role)) {
            mGoSwitchBtn.setText(R.string.go_remove);
            mRoleText.setText(getString(R.string.role) + getString(R.string.go));
        } else if ("GC".equalsIgnoreCase(role)) {
            mGoSwitchBtn.setText(R.string.go_create);
            mRoleText.setText(getString(R.string.role) + getString(R.string.gc));
        }
    }

    private void updateP2pListView() {
        if (mWiFiPeerListAdapter != null) {
            mWiFiPeerListAdapter.notifyDataSetChanged();
        }
    }

    private void updateClientListSpin() {
        if (mClientListAdapter != null) {
            mClientListAdapter.notifyDataSetChanged();
        }
    }

    public void setDeviceName(String name) {
        mDevNameText.setText(getString(R.string.dev_name) + name);
    }

    public List<String> getClientList() {
        return mClientList;
    }
}
