package cn.edu.sjtu.ble_test;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 用于进行蓝牙连接的Activity
 */
public class BLEListActivity extends Activity {

    TextView tvInfo;
    ListView lvDevList;
    Button btn_BL_Operate;

    private List<BluetoothDevice> bluetoothDeviceList = new ArrayList<>();
    private boolean blScanning;
    private BluetoothAdapter bleAdapter;
    private ArrayAdapter<String> bleListAdapter;

    private BLEBroadcastReceiver bleBroadcastReceiver = new BLEBroadcastReceiver();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.device_list);
        tvInfo = findViewById(R.id.tv_BL_info);
        lvDevList = findViewById(R.id.lv_devicesList);
        btn_BL_Operate = findViewById(R.id.btn_BL_leave);

        //获取蓝牙设备
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "BLE NOT SUPORTED!!", Toast.LENGTH_SHORT).show();
            finish();
        }

        bleAdapter = ((BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();

        if (bleAdapter == null) {
            Toast.makeText(this, "BLE NOT SUPORTED!!", Toast.LENGTH_SHORT).show();
            finish();
        }

        if (!bleAdapter.isEnabled()) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, 1);
        }

        //获取蓝牙永勋权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (this.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                int PERMISSION_REQUEST_COARSE_LOCATION = 0xb01;
                requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_REQUEST_COARSE_LOCATION);
            }
        }

        //与设备列表对应的Adapter
        bleListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, android.R.id.text1);
        lvDevList.setAdapter(bleListAdapter);

        //点击意味着选择了对应的蓝牙设备,将设备的MAC地址发回
        lvDevList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int position, long l) {
                BluetoothDevice theDevice = bluetoothDeviceList.get(position);
                scanStop();
                Intent result = new Intent();
                result.putExtra(BluetoothDevice.EXTRA_DEVICE, theDevice.getAddress());
                result.putExtra(BluetoothDevice.EXTRA_NAME, theDevice.getName());
                setResult(Activity.RESULT_OK, result);
                finish();
            }
        });

        /*
        扫描结束后通过按钮重新开始
        正在扫描时效果为暂停扫描
         */
        btn_BL_Operate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
            if (blScanning) {
                scanStop();
                btn_BL_Operate.setText("重新扫描");
            } else {
                scanStart();
            }
            }
        });

        //设置蓝色扫描的广播接收器
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        intentFilter.addAction(BluetoothDevice.ACTION_FOUND);
        registerReceiver(bleBroadcastReceiver, intentFilter);
        scanStart();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(bleBroadcastReceiver);
    }

    /**
     * 开始扫描
     */
    private void scanStart() {
        bleAdapter.startDiscovery();
        blScanning = true;
        btn_BL_Operate.setText(R.string.Stop_Scanning);
    }

    /**
     * 停止扫描
     */
    private void scanStop() {
        bleAdapter.cancelDiscovery();
        blScanning = false;
        btn_BL_Operate.setText(R.string.Scan_start);
    }

    /**
     * 广播接收器
     * 找到新蓝牙设备则更新列表,并通知界面更新
     */
    private class BLEBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice temp = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

                addDevice(temp);
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                blScanning = false;
                btn_BL_Operate.setText(R.string.Scan_start);
                Toast.makeText(BLEListActivity.this, "扫描停止", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * 用于添加设备
     */
    private void addDevice(BluetoothDevice device) {
        for (BluetoothDevice listDev : bluetoothDeviceList) {
            if (listDev.getAddress().equals(device.getAddress())) {
                return;
            }
        }
        bluetoothDeviceList.add(device);
        bleListAdapter.add("设备名:" + device.getName() + "\n设备地址:" + device.getAddress());
        tvInfo.setVisibility(View.GONE);
        bleListAdapter.notifyDataSetChanged();
    }
}
