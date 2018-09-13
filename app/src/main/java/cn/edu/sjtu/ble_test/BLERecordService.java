package cn.edu.sjtu.ble_test;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Binder;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.annotation.Nullable;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class BLERecordService extends Service {
    /**
     * 返回ACTION的各种标签
     */
    public static final String ACTION_NEW_SAMPLE = "edu.sjtu.ACTION_NEW_SAMPLE";
    public static final String ACTION_INFO = "edu.sjtu.ACTOPM_INFO";
    public static final String ACTION_DATA_AVAILABLE = "edu.sjtu.ACTION_DATA_AVAILABLE";
    public static final String ACTION_SAMPLE_STOPED = "edu.sjtu.ACTION_SAMPLE_STOPED";
    public static final String ACTION_WRONG_DEVICE = "edu.sjtu.ACTION_WRONG_DEVICE";

    public static final String SAMPLE_PERIOD = "edu.sjtu.SAMPLE_PERIOD";
    public static final String SAMPLE_COUNTS = "edu.sjtu.SAMPLE_COUNTS";
    public static final String FILE_NAME = "edu.sjtu.FILE_NAME";
    public static final String EXTRA_SAMPLE = "edu.sjtu.EXTRA_SAMPLE";
    public static final String EXTRA_MSG = "edu.sjtu.EXTRA_MSG";
    public static final String CURRENT_COUNT = "edu.sjtu.CURRENT_COUNT";

    public static final String FILE_FLODER_NAME = "/BLE_APP_DATA";

    /**
     * 默认的时间格式
     * 注意WINDOWS的文件名不能有":"
     */
    public static final String FILE_DATE_PATTERN = "yyyyMMdd HH.mm ";
    public static final String SUCCESS_MSG = "SAMPLING FINISHED SUCCESSFULLY";
    private int samplePeriod = 1;
    private int sampleCounts = 60;
    //TODO 删除各种不用的变量
    private String filename;

    private BluetoothAdapter BLEAdapter;
    private BluetoothDevice BLEDevice;
    private BluetoothGatt BLEGatt;

    private File file2save;
    private FileOutputStream outStream;
    private BluetoothGattCharacteristic target_ch;
    /**
     * 采集对象的通道性质,比较特别 0x0016
     */
    private static final int TARGET_PROPERITY = 22;

    private boolean blSampling = false;
    private boolean blAvailable = false;

    private Notification notification;
    private static final int notificationID = 100;
    /**
     * 用于Notification使用的图像*/
    private Bitmap bitmap4notification;

    private String lastValueTO;
    private String lastValueTA;

    private int currentCount = 0;

    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();

        //TODO 放置到采集的部分,而不是伴随整个service
        //保持手机待机后的CPU运行,否则会停止采集
        PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, BLERecordService.class.getName());
        wakeLock.acquire();
        //显示在标签中的图像(只decode一次,否则会报错)
        bitmap4notification = BitmapFactory.decodeResource(getResources(), R.drawable.ic_launcher_foreground);
        buildBLENotify("BLE采集服务");
        super.onCreate();
        BluetoothManager manager = (BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
        if (manager != null) {
            BLEAdapter = manager.getAdapter();
            if (BLEAdapter != null) {
                Log.e("BLE_SERVICE", "ONCREATE");
                return;     //获得了蓝牙适配器(权限)后才正常退出
            }
        }
        Log.e("BLE_SERVICE", "无法获得蓝牙设备管理器!");
        stopSelf();
    }

    /**
     * 在系统上方的标签中显示信息
     * 有助于后台Service不被杀死
     * @param msg 显示的信息
     */
    private void buildBLENotify(String msg) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
        Notification.Builder builder = new Notification.Builder(this);
        builder.setAutoCancel(false);
        builder.setSmallIcon(R.drawable.ic_launcher_foreground);
        builder.setContentIntent(pendingIntent);
        builder.setLargeIcon(bitmap4notification);
        builder.setContentTitle("BLE采集服务");
        builder.setContentText(msg);
        builder.setSubText("点击进入采集APP");
        notification = builder.build();
        startForeground(notificationID, notification);
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        sampleCounts = intent.getIntExtra(BLERecordService.SAMPLE_COUNTS, 10);
        samplePeriod = intent.getIntExtra(BLERecordService.SAMPLE_PERIOD, 10);
        filename = intent.getStringExtra(BLERecordService.FILE_NAME);
        startSampling(samplePeriod, sampleCounts, filename);
        return START_STICKY;    //return START_STICKY有助于service在后台活动
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder; //返回service实例的提取器
    }

    private final IBinder mBinder = new localBinder();
    class localBinder extends Binder{
        BLERecordService getService() {
            return BLERecordService.this;
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.e("BLE_SERVICE", "UNBIND");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (BLEGatt != null) {
            BLEGatt.disconnect();
        }
        //结束时销毁进程锁
        if (wakeLock != null) {
            wakeLock.release();
            wakeLock = null;
        }
        Log.e("BLE_SERVICE", "DESTROYED");
    }

    public boolean isDataAvailable() {
        return blAvailable;
    }

    public boolean isSampling() {
        return blSampling;
    }

    public File getFile() {
        return file2save;
    }

    public String getlastValueTO() {
        return lastValueTO;
    }

    public String getlastValueTA() {
        return lastValueTA;
    }

    /**
     * 连接设备,注意为异步连接,在callback中完成相关工作
     * @param devadd 设备MAC 地址
     * @return 是否通过初步检查
     */
    boolean connect(String devadd) {
        try {
            if (BLEGatt != null) {
                BLEGatt.disconnect();
            }
            blSampling = false;
            blAvailable = false;
            BLEDevice = BLEAdapter.getRemoteDevice(devadd);
            BLEGatt = BLEDevice.connectGatt(this, false, bleCallBack);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * 断开连接,延迟一段时间再断开(避免触发readcharacteristic后断开导致错误
     */
    void disconnect() {
        blAvailable = false;
        blSampling = false;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(500);
                    if (BLEGatt != null) {
                        BLEGatt.disconnect();
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    /**
     * 开始采集线程
     * @param period 采样周期(s)
     * @param counts 采样数量
     * @param filename 保存的文件名
     * @return 是否成功开始
     */
    public boolean startSampling(int period, int counts, String filename) {
        try {
            if (!blAvailable) {
                Log.e("BLE_SERVICE", "Start sampling before data available");
                return false;
            }
            String saveFloder = Environment.getExternalStorageDirectory().getAbsolutePath() + FILE_FLODER_NAME;
            File floder = new File(saveFloder);
            if (!floder.exists()) {
                if (!floder.mkdirs()) {
                    sendInfoBack("文件夹创建失败!!");
                    return false;
                }
            }
            file2save = new File(saveFloder, filename + ".xls");
            if (!file2save.exists()) {
                if(!file2save.createNewFile()) {
                    sendInfoBack("文件创建失败!!");
                    return false;
                }
            }
            outStream = new FileOutputStream(file2save);
            String sampleTime = new SimpleDateFormat(BLERecordService.FILE_DATE_PATTERN).format(new Date(System.currentTimeMillis()));
            //写入前两行配置内容与TA TO标签
            outStream.write(("Start time:\t" + sampleTime + "\tSampling Period(s)\t" + period + "\tExpected counts:\t" + counts + "\r\nTO\tTA\r\n") .getBytes());
            //开始采集线程
            blSampling = true;
            Thread th2readData = new Thread(runnable2ReadData); //TODO 调整顺序
            th2readData.start();
            samplePeriod = period;
            sampleCounts = counts;
            return true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    void stopSampling() {
        sampleStopWork();
    }

    private BluetoothGattCallback bleCallBack = new BluetoothGattCallback() {
        @Override
        //连接成功后才可以开始discoverservices,因为为异步
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            switch (newState) {
                case BluetoothProfile.STATE_CONNECTED:
                    gatt.discoverServices();
                    break;
                case BluetoothProfile.STATE_DISCONNECTED:
                    Log.e("BLE_SERVICE", "device disconnected");
                    break;
            }
        }

        @Override
        //发现service完毕后寻找符合TARGET_PROPERITY的通道,如果发现就尝试读取
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);
            List<BluetoothGattService> BLEServiceList = gatt.getServices();
            for (int i = 0; i <  BLEServiceList.size(); i++) {
                BluetoothGattService tmp = BLEServiceList.get(i);
                List<BluetoothGattCharacteristic> chlist = tmp.getCharacteristics();
                for (BluetoothGattCharacteristic ch : chlist) {
                    if(ch.getProperties() == TARGET_PROPERITY) {
                        target_ch = ch;
                        BLEGatt.readCharacteristic(target_ch);     //第一次尝试读取
                        return;
                    }
                }
            }
            sendBroadcastBack(ACTION_WRONG_DEVICE);
            Log.e("BLE_SERVICE", "NO right characteristic! Miss match device?");
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicRead(gatt, characteristic, status);

            if (blAvailable) {
                //如果不是第一次读取,说明正在采集,记录数据
                writeDownData(characteristic);
            } else {
                //如果是第一次读到,说明通道正确,发布信息,可以开始采集
                blAvailable = true;
                Intent intent = new Intent(ACTION_DATA_AVAILABLE);
                LocalBroadcastManager.getInstance(BLERecordService.this).sendBroadcast(intent);
            }
        }
    };

    /**
     * 采集线程
     */
    private Runnable runnable2ReadData = new Runnable() {
        int thecounts;
        @Override
        public void run() {
            try {
                Thread.sleep(200);  //开始时先等200ms
            } catch (Exception e) {
                e.printStackTrace();
            }
            thecounts = sampleCounts;
            for (currentCount = 0; currentCount < thecounts; currentCount++) {
                if (!blSampling) {
                    return; //TODO 文件中记录人为停止采集
                }
                try {
                    int lostCount = 0;
                    while (!BLEGatt.connect()) {
                        lostCount ++;
                        if (lostCount > 60) {   //连续1分钟连接失败则停止采集
                            Log.w("BLE_Thread", "lost connection!");
                            sendInfoBack("连接丢失! 记录自动停止");
                            blSampling = false;
                            // TODO 文件中记录丢失发生
                        }
                        Thread.sleep(1000);
                    }
                    BLEGatt.readCharacteristic(target_ch);  //启动采集(异步)
                    Thread.sleep(samplePeriod * 1000);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            sendInfoBack("采集完毕");
            Log.e("BLE_THREAD", SUCCESS_MSG);
            sampleStopWork();
        }
    };

    /**
     * 结束采集的工作, 关闭文件输出等
     */
    private void sampleStopWork() {
        blSampling = false;
        blAvailable = false;
        sendBroadcastBack(ACTION_SAMPLE_STOPED);
        try {
            outStream.flush();
            outStream.close();
            Uri uri = Uri.fromFile(file2save);
            Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri);
            this.sendBroadcast(intent);
            BLEGatt.disconnect();
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private void sendInfoBack(String msg) {
        sendBroadcastBack(ACTION_INFO, msg);
    }

    private void sendBroadcastBack(String act) {
        sendBroadcastBack(act, null);
    }

    /**
     * 返回Action的模板
     * @param act 返回的Action的类型
     * @param msg 返回的Extra msg
     */
    private void sendBroadcastBack(String act, String msg) {
        Intent intent = new Intent(act);
        if (msg != null) {
            intent.putExtra(EXTRA_MSG, msg);
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    /**
     * 记录数据
     */
    void writeDownData(BluetoothGattCharacteristic characteristic) {
        if (!blSampling) {
            return;
        }
        try {
            String msg = new String(characteristic.getValue(), "ascii");
            buildBLENotify(msg);
//            lastValueTO = msg.substring(4,9);
//            lastValueTA = msg.substring(13,18);
            String[] msgs = msg.split("\t");
            lastValueTO = msgs[0];
            if (msgs.length >= 2) {
                lastValueTA = msgs[1];
            } else {
                lastValueTA = "0";
            }
            //TODO 停止输出时间项
            String formatS = lastValueTO + "\t" + lastValueTA + "\t" +
                    SimpleDateFormat.getTimeInstance().format(new Date(System.currentTimeMillis()));
            outStream.write((formatS + "\r\n").getBytes());
            Log.i("BLE_RECORD", "data got:" + msg);
            //新采样返回给Activity
            Intent intent = new Intent(ACTION_NEW_SAMPLE);
            intent.putExtra(EXTRA_SAMPLE, formatS);
            intent.putExtra(CURRENT_COUNT, currentCount);
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
