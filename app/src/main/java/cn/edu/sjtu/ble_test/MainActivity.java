package cn.edu.sjtu.ble_test;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.AxisBase;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.IAxisValueFormatter;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.LineNumberReader;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {
    /**
     * 可能用到的权限列表,主要为文件存取和地点获取(蓝牙用)
     */
    private static final String[] PERMISSION_NEED=
            {Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ,Manifest.permission.WRITE_CONTACTS
                    ,Manifest.permission.ACCESS_COARSE_LOCATION
                    ,Manifest.permission.BLUETOOTH
                    ,Manifest.permission.BLUETOOTH_ADMIN
                    ,Manifest.permission.WAKE_LOCK};

    private static final int REQUEST_DEVICE_INFO = 0;
    String devAdd;
    String BLE_name;

    private BLERecordService bleRecordService;

    Button btnConnect;
    Button btnRecord;
    Button btnInput;
    TextView tvCurrentValueTA;
    TextView tvCurrentValueTO;
    TextView tvDebug;
    CheckBox cbTA;
    CheckBox cbTO;

    /**
     * 开源图标控件,MPAndroidChart
     */
    LineChart lineChart;

    Handler handler;

    enum STATE {NOT_CONNECTED, CONNETCED, SAMPLING, NO_SERVICE}
    private STATE state;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);  //TODO 尝试消除标题栏,但是失效
        setContentView(R.layout.activity_main);
        btnConnect = findViewById(R.id.btn_connect);
        btnRecord = findViewById(R.id.btn_record);
        btnInput = findViewById(R.id.btn_input);
        tvCurrentValueTA = findViewById(R.id.tvCurrentValue1);
        tvCurrentValueTO = findViewById(R.id.tvCurrentValue2);
        tvDebug = findViewById(R.id.tv4debug);
        lineChart = findViewById(R.id.lineChart);
        cbTA = findViewById(R.id.cb_showTA);
        cbTO = findViewById(R.id.cb_showTO);

        lineChartSetting();

        handler = new Handler();

        //onClickListener都设置在MainActivity中
        btnConnect.setOnClickListener(this);
        btnInput.setOnClickListener(this);
        btnRecord.setOnClickListener(this);
        cbTO.setOnClickListener(this);
        cbTA.setOnClickListener(this);

        //接收来自service的Intent广播
        LocalBroadcastManager.getInstance(this).registerReceiver(fromBLERECORD, makeFilterFromBLERECORD());

        //新版本开始的动态设置权限,非常重要
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean gotPermit = false;
            for (String permission: PERMISSION_NEED) {
                gotPermit &= ((checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED));
            }
            if (!gotPermit) {
                ActivityCompat.requestPermissions(MainActivity.this, PERMISSION_NEED, 1);
            }
        }

        //绑定服务,与服务建立连接
        Intent callIntent = new Intent(MainActivity.this, BLERecordService.class);
        bindService(callIntent, serviceConnect, BIND_AUTO_CREATE | BIND_ABOVE_CLIENT);
        change2State(STATE.NO_SERVICE);
    }

    @Override
    protected void onDestroy() {
        //断开和service的连接
        unbindService(serviceConnect);
        super.onDestroy();  //TODO 移动到末尾处

        //停止广播接收,否则每次打开界面都会重复注册广播接收器
        LocalBroadcastManager.getInstance(this).unregisterReceiver(fromBLERECORD);

        //TODO 如果不在采集,停止service
//        if(state != STATE.SAMPLING) {
//            bleRecordService.stopService(new Intent(this, BLERecordService.class));
//        }
    }

    /**
     * 切换状态,不同状态下按钮的选择与功能会变化
     * @param newstate 新的状态
     */
    void change2State(STATE newstate) {
        state = newstate;
        switch (newstate) {
            case NOT_CONNECTED:
                btnRecord.setEnabled(false);
                btnConnect.setEnabled(true);
                btnConnect.setText("选择设备");
                btnInput.setEnabled(true);
                btnInput.setText("回看数据");
                bleRecordService.disconnect();
                blSampingAndCharting = false;
                break;
            case CONNETCED:
                btnRecord.setEnabled(true);
                btnRecord.setText("采集数据");
                btnConnect.setEnabled(true);
                btnConnect.setText("选择设备");
                btnInput.setEnabled(false);
                blSampingAndCharting = false;
                break;
            case SAMPLING:
                btnRecord.setEnabled(true);
                btnRecord.setText("停止采集");
                btnConnect.setEnabled(false);
                btnInput.setEnabled(true);
                btnInput.setText("导入已有数据");
                break;
                //如果service没有建立成功会停留在此状态
            case NO_SERVICE:
                btnInput.setEnabled(false);
                btnRecord.setEnabled(false);
                btnConnect.setEnabled(false);
                blSampingAndCharting = false;
                break;
        }
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        //连接按钮,启动蓝牙设备选择界面,onActivityResult方法处回应
        if (id == btnConnect.getId()) {
            change2State(STATE.NOT_CONNECTED);
            Intent intent_temp = new Intent(MainActivity.this, BLEListActivity.class);
            startActivityForResult(intent_temp, REQUEST_DEVICE_INFO);
        }
        //采集按钮,控制采集开始与手动停止
        else if (id == btnRecord.getId()) {
            switch (state) {
                case CONNETCED:
                    //使用自建的dialog类输入参数
                    final RecordDialog dialog = new RecordDialog(MainActivity.this);
                    dialog.setTitle("设置采样参数");
                    dialog.show();
                    break;
                    //如果正在采集,则结束采集
                case SAMPLING:
                    change2State(STATE.NOT_CONNECTED);
                    bleRecordService.stopSampling();
                    dataFile = new DataFile(bleRecordService.getFile());    //TODO 检查file是否真实存在
                    sendInfo2tvDebug("本次采集结束");
                    bleRecordService.disconnect();
                    break;
            }
        }
        else if (id == btnInput.getId()) {
            switch (state) {
                case NOT_CONNECTED:
                    //如果没有连接,从文件夹中选取文件
                    FileOpenDialog filedialog = new FileOpenDialog(MainActivity.this);
                    filedialog.setTitle("请选择回看的数据");
                    filedialog.show();
                    break;
                case SAMPLING:
                    //根据这个boolean判断是否实时显示更新数据
                    //因为第一次读取从文件读取,而之后直接从service发布的广播中获得
                    if (!blSampingAndCharting) {
                        dataFile = new DataFile(bleRecordService.getFile());
                        btnInput.setText("停止实时更新");
                    } else {
                        blSampingAndCharting = false;
                        btnInput.setText("导入已有数据");
                    }
                    break;
            }
        }
        //选择linechart是否显示对应参数
        else if (id == cbTA.getId()) {
            dataFile.setDataSet(dataFile.TASet, cbTA.isChecked());
        }
        else if (id == cbTO.getId()) {
            dataFile.setDataSet(dataFile.TOSet, cbTO.isChecked());
        }
    }

    //目前仅有蓝牙设备App的Activity返回
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case REQUEST_DEVICE_INFO:
                if (resultCode == Activity.RESULT_OK && data != null) {
                    devAdd = data.getStringExtra(BluetoothDevice.EXTRA_DEVICE);
                    BLE_name = data.getStringExtra(BluetoothDevice.EXTRA_NAME);
                    //通过服务验证是否存在正确的数据格式, 验证时主界面关闭所有按钮
                    if (!bleRecordService.connect(devAdd)) {
                        sendInfo2tvDebug(BLE_name + "连接失败");
                        return;
                    }
                    sendInfo2tvDebug("验证设备 " + BLE_name + " ...");
                    change2State(STATE.NO_SERVICE);
                    //过5秒如果没有得到正确的数据格式, 就恢复未连接状态, 要求重新连接设备
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Thread.sleep(5000);
                                if (!bleRecordService.isDataAvailable()) {
                                    sendInfo2tvDebug("读取数据超时!请重新连接蓝牙");
                                    handler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            change2State(STATE.NOT_CONNECTED);
                                        }
                                    });
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }).start();
                }
        }
    }

    /**
     * 服务连接状态改变接收器
     * 关闭屏幕Activity会被销毁,重新连接时service可能正在采集
     */
    ServiceConnection serviceConnect = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            bleRecordService = ((BLERecordService.localBinder)iBinder).getService();//获取service实例
            if (bleRecordService.isSampling()) {
                change2State(STATE.SAMPLING);
            } else {
                change2State(STATE.NOT_CONNECTED);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            change2State(STATE.NOT_CONNECTED);
        }
    };

    /**
     * @return 建立从service返回的广播的过滤器
     */
    private static IntentFilter makeFilterFromBLERECORD() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BLERecordService.ACTION_NEW_SAMPLE);
        intentFilter.addAction(BLERecordService.ACTION_INFO);
        intentFilter.addAction(BLERecordService.ACTION_DATA_AVAILABLE);
        intentFilter.addAction(BLERecordService.ACTION_SAMPLE_STOPED);
        intentFilter.addAction(BLERecordService.ACTION_WRONG_DEVICE);
        return intentFilter;
    }

    /**
     * service返回信息的处理
     */
    private final BroadcastReceiver fromBLERECORD = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                return;
            }
            switch (action) {
                //第一次连接得到正确数据后发生
                case BLERecordService.ACTION_DATA_AVAILABLE:
                    change2State(STATE.CONNETCED);
                    sendInfo2tvDebug("数据验证成功,可以准备开始采集");
                    break;
                //返回信息需要直接在右下角显示, 更多为debug用
                case BLERecordService.ACTION_INFO:
                    sendInfo2tvDebug(intent.getStringExtra(BLERecordService.EXTRA_MSG));
                    break;
                //得到了新的数据点
                case BLERecordService.ACTION_NEW_SAMPLE:
//                    String newSample = intent.getStringExtra(BLERecordService.EXTRA_SAMPLE);
                    //第几个采样点,用于绘图
                    int currentCount = intent.getIntExtra(
                            BLERecordService.CURRENT_COUNT, 0);
                    String strTO = bleRecordService.getlastValueTO();
                    String strTA = bleRecordService.getlastValueTA();
                    float fTO = Float.parseFloat(strTO);
                    float fTA = Float.parseFloat(strTA);
                    String msg = "Main receive:" + fTO + "\t" + fTA + "\t";
                    Log.i("BLE_MAIN", msg);
                    //加上当前的时间,用于debug
                    msg += SimpleDateFormat.getTimeInstance().format(
                            new Date(System.currentTimeMillis()));
                    tvCurrentValueTO.setText(strTO);
                    tvCurrentValueTA.setText(strTA);
                    //如果正在画图,则将新的数据插入图表中
                    if (blSampingAndCharting) {
                        LineData linedata = lineChart.getData();
                        int TAIndex = linedata.getIndexOfDataSet(dataFile.TASet);
                        int TOIndex = linedata.getIndexOfDataSet(dataFile.TOSet);
                        linedata.addEntry(new Entry(currentCount, fTA), TAIndex);
                        linedata.addEntry(new Entry(currentCount, fTO), TOIndex);
                        lineChart.notifyDataSetChanged();
                        lineChart.invalidate();
                    }
                    sendInfo2tvDebug(msg);
                    break;
                //采集结束时发生
                case BLERecordService.ACTION_SAMPLE_STOPED:
                    change2State(STATE.NOT_CONNECTED);
                    break;
                //连接成功,但是没有找到合适的数据
                case BLERecordService.ACTION_WRONG_DEVICE:
                    change2State(STATE.NOT_CONNECTED);
                    sendInfo2tvDebug("读取不到有效数据!请重新连接蓝牙");
                    break;
            }
        }
    };

    /**
     * 输入采集参数的dialog
     * TODO 迁移到一个新的文件中
     */
    private class RecordDialog extends Dialog {
        EditText etSamplePeriod;
        EditText etSampleTotal;
        Spinner spSampleperiod;
        Spinner spSampleTotal;
        Button btnStart;
        Button btnCancel;
        TextView tvInfo;
        EditText etFileName;

        String filename;

        int sample_period;
        int sample_count;

        RecordDialog(Context context) {
            super(context);

            setCancelable(false);

            setContentView(R.layout.recor_setting);

            etSamplePeriod = findViewById(R.id.etSamplePeriodValue);
            etSampleTotal = findViewById(R.id.etSampleTotalTimeValue);
            spSampleperiod = findViewById(R.id.spSamplePeriodScale);
            spSampleTotal = findViewById(R.id.spSampleTotalTimeScale);
            btnStart = findViewById(R.id.btnSettingStart);
            btnCancel = findViewById(R.id.btnSettingCancel);
            tvInfo = findViewById(R.id.tvSettingInfo);
            etFileName = findViewById(R.id.etFileName);

            //文件名的默认格式为"时间 + 设备名"
            filename = new SimpleDateFormat(BLERecordService.FILE_DATE_PATTERN).format(new Date(System.currentTimeMillis())) + BLE_name;
            etFileName.setText(filename);

            //用于实时计算采样点数量
            etSampleTotal.setOnEditorActionListener(onEditorActionListener);
            etSamplePeriod.setOnEditorActionListener(onEditorActionListener);
            spSampleTotal.setOnItemSelectedListener(onItemSelectedListener);
            spSampleperiod.setOnItemSelectedListener(onItemSelectedListener);
            etSampleTotal.setOnFocusChangeListener(onFocusChangeListener);
            etSamplePeriod.setOnFocusChangeListener(onFocusChangeListener);
            spSampleTotal.setOnFocusChangeListener(onFocusChangeListener);
            spSampleperiod.setOnFocusChangeListener(onFocusChangeListener);

            btnCancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    dismiss();
                }
            });

            btnStart.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    sample_count = calSampleCounts();
                    if (sample_count <= 0) {
                        return;
                    }
                    sendInfo2tvDebug("counts:" + sample_count + "period:" + sample_period);
                    Intent intent = new Intent(MainActivity.this, BLERecordService.class);
                    //启动服务需要文件名,采样间隔(s)和采样数量
                    intent.putExtra(BLERecordService.SAMPLE_COUNTS, sample_count);
                    intent.putExtra(BLERecordService.SAMPLE_PERIOD, sample_period);
                    intent.putExtra(BLERecordService.FILE_NAME, filename);
                    startService(intent);   //调用startService后,service不会随着activity结束而结束
                    change2State(STATE.SAMPLING);   //TODO 开始采集时才change state
                    dismiss();
                }
            });
        }

        /**
         * 用于实时计算采样点数
         */
        private View.OnFocusChangeListener onFocusChangeListener = new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean b) {
                calSampleCounts();
            }
        };

        /**
         * 用于实时计算采样点数
         */
        private AdapterView.OnItemSelectedListener onItemSelectedListener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                calSampleCounts();
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        };

        /**
         * 用于实时计算采样点数
         */
        private TextView.OnEditorActionListener onEditorActionListener = new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView textView, int i, KeyEvent keyEvent) {
                calSampleCounts();
                return true;
            }
        };

        /**
         * 根据当前的选择计算采样点数量
         * @return 计算得到的采样点数量,如果计算有问题,返回-1
         */
        private int calSampleCounts() {
            try {
                int periodValue = Integer.parseInt(etSamplePeriod.getText().toString());
                int totalValue = Integer.parseInt(etSampleTotal.getText().toString());
                int periodScale = getScale(spSampleperiod);
                int totalScale = getScale(spSampleTotal);
                sample_period = periodScale * periodValue;
                sample_count = totalScale * totalValue / sample_period;
                tvInfo.setText("一共采样" + sample_count + "次");
                btnStart.setEnabled(true);
                filename = etFileName.getText().toString();
                return sample_count;
            } catch (Exception e) {
                e.printStackTrace();
                tvInfo.setText("一共采样---次");
                btnStart.setEnabled(false);
                return -1;
            }
        }

        /**
         * 计算当前选项的缩放倍数(相比秒而言)
         * @param spinner 哪个下拉菜单?
         * @return  当前菜单对应的缩放倍数
         */
        private int getScale(Spinner spinner) {
            String selectedStr = ((TextView)spinner.getSelectedView()).getText().toString();
            if (selectedStr.equals("秒")) {  //TODO 使用switch
                return 1;
            } else if (selectedStr.equals("分钟")) {
                return 60;
            } else if (selectedStr.equals("小时")) {
                return 3600;
            } else if (selectedStr.equals("天")) {
                return 3600 * 24;
            } else {
                Toast.makeText(MainActivity.this, "WRONG SPINNER!!", Toast.LENGTH_SHORT).show();
                return 0;
            }
        }
    }

    /**
     * 选择打开文件的dialog(DOCUMENTS)文件夹
     * TODO 移动到单一文件中
     */
    private class FileOpenDialog extends Dialog {

        ListView lvFiles;
        File dirDOCUMENT;

        FileOpenDialog(@NonNull Context context) {
            super(context);
            setContentView(R.layout.file_list);
            lvFiles = findViewById(R.id.lvFiles);
            dirDOCUMENT = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + BLERecordService.FILE_FLODER_NAME);
            final File[] files = dirDOCUMENT.listFiles();
            if (files == null) {
                sendInfo2tvDebug(BLERecordService.FILE_FLODER_NAME + "中没有文件!");
                return;
            }
            if (files.length == 0) {
                Toast.makeText(MainActivity.this, "Document 文件夹下没有文件", Toast.LENGTH_SHORT).show();
                dismiss();
            }
            ArrayAdapter<String> fileList= new ArrayAdapter<>(MainActivity.this, android.R.layout.simple_list_item_1, android.R.id.text1);
            lvFiles.setAdapter(fileList);
            //更新文件的状态,使得电脑上可以直接看到文件
            for (File theFile : files) {
                fileList.add(theFile.getName());
                Uri uri = Uri.fromFile(theFile);
                Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri);
                sendBroadcast(intent);
            }

            lvFiles.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                    sendInfo2tvDebug(files[i].getName());
                    dataFile = new DataFile(files[i]);
                    dismiss();
                }
            });
        }
    }

    private DataFile dataFile;

    boolean blSampingAndCharting = false;

    /**
     * 显示linechart使用的文件
     * TODO 命名和流程优化
     */
    private class DataFile {
        Date sampleStartTime;   //文件对应的开始时间,用于计算标签显示内容
        int samplePeriod;
        int sampleTotoal;
        int sampleExpected;
        FileReader freader;
        LineNumberReader lreader;
        List<Float> TAlist = new ArrayList<>();
        List<Float> TOlist = new ArrayList<>();
        List<Entry> TAEntryList = new ArrayList<>();
        List<Entry> TOEntryList = new ArrayList<>();
        long startTime;
        LineDataSet TASet;
        LineDataSet TOSet;
        LineData lineData = new LineData();

        DataFile(File file) {
            String tempStrLine;
            try {
                if (lreader != null) {
                    lreader.close();
                }
                if (freader != null) {
                    freader.close();
                }
                freader = new FileReader(file);
                lreader = new LineNumberReader(freader);
                //读取第一行,得到开始时间,采样周期和采样总数
                tempStrLine = lreader.readLine();
                if (tempStrLine == null) {
                    sendInfo2tvDebug("数据格式错误");
                    return;
                }
                findParas(tempStrLine);
                inputData();
                outputData2Chart();
            } catch (Exception e) {
                e.printStackTrace();
                sendInfo2tvDebug("文件格式错误\n" + e.toString());
            }
            //如果在采集,默认打开实时显示
            if (state == STATE.SAMPLING) {
                blSampingAndCharting = true;
            }
        }

        /**
         * 读取第一行,得到开始时间,采样周期和采样总数
         * @param paraStr 第一行文本
         */
        void findParas(String paraStr) throws ParseException, IOException {
            String[] paras = paraStr.split("\t");
            sampleStartTime = new SimpleDateFormat(BLERecordService.FILE_DATE_PATTERN).parse(paras[1]);
            samplePeriod = Integer.parseInt(paras[3]);
            sampleExpected = Integer.parseInt(paras[5]);
            startTime = sampleStartTime.getTime();
            lreader.readLine(); //第二行丢掉 "TO TA"
        }

        /**
         * 读入所有的数据到两个list中
         */
        void inputData() throws IOException {
            while (true) {
                String tempStrLine = lreader.readLine();
                if (tempStrLine == null) {
                    break;
                }
                String[] Values = tempStrLine.split("\t");
                float temp = Float.parseFloat(Values[0]);
                TOlist.add(temp);
                temp = Float.parseFloat(Values[1]);
                TAlist.add(temp);
            }
            sampleTotoal = lreader.getLineNumber() - 2;
            lreader.close();
            freader.close();
        }

        /**
         * 生成自定义的标签
         * @return 合适当前文件的标签计算器
         */
        IAxisValueFormatter buildLables() {
            IAxisValueFormatter valueFormatter = new IAxisValueFormatter() {
                @Override
                public String getFormattedValue(float value, AxisBase axis) {
                    long timeAtValue = startTime + (long)value * 1000 * samplePeriod;
                    return new SimpleDateFormat("dd日HH:mm").format(timeAtValue);
                }
            };
            return valueFormatter;
        }

        /**
         * 将数据打包发给linechart
         * 和显示相关的设置大多在这里,而不是linechart本身
         */
        void outputData2Chart() {
            for ( int i = 0; i < sampleTotoal; i++) {
                TAEntryList.add(new Entry(i, TAlist.get(i)));
                TOEntryList.add(new Entry(i, TOlist.get(i)));
                i++;
            }
            TASet = new LineDataSet(TAEntryList, "TA");
            TOSet = new LineDataSet(TOEntryList, "TO");
            TASet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
            TOSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
            TASet.setCircleRadius(1);
            TASet.setDrawValues(false);
            TOSet.setCircleRadius(1);
            TOSet.setDrawValues(false);
            TOSet.setColor(Color.RED);
            TOSet.setCircleColor(Color.RED);
            xAxis.setValueFormatter(buildLables());
            setDataSet(TASet, true);
            setDataSet(TOSet, true);
            lineChart.animateX(400);
        }

        /**
         * 显示或隐藏对应的dataset(check box的功能实现)
         * @param targetSet 目标的dataset
         * @param add 是否显示
         */
        void setDataSet(LineDataSet targetSet, boolean add) {
            if (add) {
                lineData.addDataSet(targetSet);
            } else {
                lineData.removeDataSet(targetSet);
            }
            lineChart.setData(lineData);

            lineChart.invalidate();
        }
    }

    YAxis yAxis;
    XAxis xAxis;

    /**
     * 图表控件设置
     */
    private void lineChartSetting() {
        yAxis = lineChart.getAxisLeft();
        xAxis = lineChart.getXAxis();
        lineChart.setNoDataText("暂无数据显示");
        lineChart.setDragEnabled(true);
        lineChart.setScaleXEnabled(true);
        lineChart.setScaleYEnabled(true);
        lineChart.setContentDescription("");
    }

    /**
     * 主界面右下角显示文字, 覆盖之前的内容
     * @param msg 要在右下角显示的msg
     */
    private void sendInfo2tvDebug(final String msg) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                tvDebug.setText(msg);
            }
        });
    }
}
