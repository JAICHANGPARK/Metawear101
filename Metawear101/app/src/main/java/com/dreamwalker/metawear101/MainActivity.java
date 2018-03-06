package com.dreamwalker.metawear101;

import android.app.Dialog;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import com.mbientlab.metawear.Data;
import com.mbientlab.metawear.DeviceInformation;
import com.mbientlab.metawear.MetaWearBoard;
import com.mbientlab.metawear.Route;
import com.mbientlab.metawear.Subscriber;
import com.mbientlab.metawear.android.BtleService;
import com.mbientlab.metawear.builder.RouteBuilder;
import com.mbientlab.metawear.builder.RouteComponent;
import com.mbientlab.metawear.module.Accelerometer;
import com.mbientlab.metawear.module.AccelerometerBmi160;
import com.mbientlab.metawear.module.Settings;

import java.util.Locale;

import bolts.Continuation;
import bolts.Task;

import static com.dreamwalker.metawear101.MWScanActivity.setConnInterval;

public class MainActivity extends AppCompatActivity implements ServiceConnection, ModuleFragmentBase.FragmentBus {

    public final static String EXTRA_BT_DEVICE = "com.dreamwalker.metawear101.EXTRA_BT_DEVICE";
    public final static String EXTRA_BT_ADRESS = "com.dreamwalker.metawear101.EXTRA_BT_ADDRESS";

    private static final int SELECT_FILE_REQ = 1, PERMISSION_REQUEST_READ_STORAGE = 2;
    private static final String EXTRA_URI = "uri", FRAGMENT_KEY = "com.mbientlab.metawear.app.NavigationActivity.FRAGMENT_KEY",
            DFU_PROGRESS_FRAGMENT_TAG = "com.mbientlab.metawear.app.NavigationActivity.DFU_PROGRESS_FRAGMENT_TAG";


    private static final String TAG = "MainActivity";
    private BtleService.LocalBinder serviceBinder;
    private final String MW_MAC_ADDRESS = "EB:65:98:6E:6B:2F";
    private String MW_ADDRESS = null;
    private MetaWearBoard board;
    private Accelerometer accelerometer;
    Settings settings;
    private AccelerometerBmi160 accBmi160;
    //AccelerometerBmi160.StepDetectorDataProducer stepDetector;
    public Handler handler = null;
    TextView stepText;
    TextView manufacturerValue, modelNumberValue, serialNumberValue;
    TextView firmwareRevisionValue, hardwareRevisionValue, deviceMacAddressValue;

    private final String RECONNECT_DIALOG_TAG = "reconnect_dialog_tag";
    private final Handler taskScheduler = new Handler();
    private BluetoothDevice btDevice;
    private Fragment currentFragment = null;

    TextView board_rssi_text;
    TextView board_rssi_value;
    TextView board_battery_level_text;
    TextView board_battery_level_value;

    int step = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        handler = new Handler();

        MW_ADDRESS = getIntent().getStringExtra(EXTRA_BT_ADRESS);
        btDevice = getIntent().getParcelableExtra(EXTRA_BT_DEVICE);

        Log.e(TAG, "onCreate: - MW_ADDRESS " + MW_ADDRESS);
        Log.e(TAG, "onCreate: - EXTRA_BT_DEVICE " + btDevice);


        // Bind the service when the activity is created
        getApplicationContext().bindService(new Intent(this, BtleService.class),
                this, Context.BIND_AUTO_CREATE);

        stepText = (TextView) findViewById(R.id.step_count);
        Button startButton = (Button) findViewById(R.id.start_accel);
        Button stopButton = (Button) findViewById(R.id.stop_accel);
        board_rssi_text = (TextView) findViewById(R.id.board_rssi_text);
        board_rssi_value = (TextView) findViewById(R.id.board_rssi_value);
        board_battery_level_text = (TextView) findViewById(R.id.board_battery_level_text);
        board_battery_level_value = (TextView) findViewById(R.id.board_battery_level_value);


        manufacturerValue = (TextView) findViewById(R.id.manufacturer_value);
        modelNumberValue = (TextView) findViewById(R.id.model_number_value);
        serialNumberValue = (TextView) findViewById(R.id.serial_number_value);
        firmwareRevisionValue = (TextView) findViewById(R.id.firmware_revision_value);
        hardwareRevisionValue = (TextView) findViewById(R.id.hardware_revision_value);
        deviceMacAddressValue = (TextView) findViewById(R.id.device_mac_address_value);


        board_rssi_text.setOnClickListener(v ->
                board.readRssiAsync().continueWith(task -> {
                    board_rssi_value.setText(String.format(Locale.US, "%d dBm", task.getResult()));
                    return null;
                }, Task.UI_THREAD_EXECUTOR)
        );

        board_battery_level_text.setOnClickListener(v ->
                board.readBatteryLevelAsync().continueWith(task -> {
                    board_battery_level_value.setText(String.format(Locale.US, "%d", task.getResult()));
                    return null;
                }, Task.UI_THREAD_EXECUTOR)
        );


        startButton.setOnClickListener(v -> {
            //accelerometer.acceleration().start();
            //accelerometer.start();

        });

        stopButton.setOnClickListener(v -> {
            accBmi160.stop();
            accBmi160.stepDetector().stop();
            //accelerometer.stop();
            //accelerometer.acceleration().stop();
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Unbind the service when the activity is destroyed
        getApplicationContext().unbindService(this);
        Log.e(TAG, "unbindService: " + "Service Unbinding");
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        //serviceBinder = (BtleService.LocalBinder) service;
        board = ((BtleService.LocalBinder) service).getMetaWearBoard(btDevice);
        Log.e(TAG, "onServiceConnected: " + "Service Conn");
        board.onUnexpectedDisconnect(status -> attemptReconnect());
        retrieveBoard();
    }


    @Override
    public void onServiceDisconnected(ComponentName name) {

//        board.disconnectAsync().continueWith(new Continuation<Void, Void>() {
//            @Override
//            public Void then(Task<Void> task) throws Exception {
//                Log.i("MainActivity", "Disconnected");
//                return null;
//            }
//        });
    }

    public void setup() {

        board.connectAsync().onSuccessTask(new Continuation<Void, Task<Route>>() {
            @Override
            public Task<Route> then(Task<Void> task) throws Exception {

                Log.e("MainActivity", "Connected");

                accBmi160 = board.getModule(AccelerometerBmi160.class);
                accBmi160.stepDetector().configure()
                        .mode(AccelerometerBmi160.StepDetectorMode.NORMAL)
                        .commit();

                board.readDeviceInformationAsync()
                        .continueWith((Continuation<DeviceInformation, Void>) task1 -> {
                            Log.e("MainActivity", "Device Information: " + task1.getResult());

                            manufacturerValue.setText(task1.getResult().manufacturer);
                            modelNumberValue.setText(task1.getResult().modelNumber);
                            serialNumberValue.setText(task1.getResult().serialNumber);
                            firmwareRevisionValue.setText(task1.getResult().firmwareRevision);
                            hardwareRevisionValue.setText(task1.getResult().hardwareRevision);
                            deviceMacAddressValue.setText(board.getMacAddress());

                            return null;
                        }, Task.UI_THREAD_EXECUTOR);


                return accBmi160.stepDetector().addRouteAsync(new RouteBuilder() {
                    @Override
                    public void configure(RouteComponent source) {
                        source.stream(new Subscriber() {
                            @Override
                            public void apply(Data data, Object... env) {
                                step += 1;

                                handler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        stepText.setText(String.valueOf(step));
                                    }
                                });

                                Log.e("MainActivity", "Took a step");
                            }
                        });
                    }
                });
            }
        }).continueWith(new Continuation<Route, Void>() {
            @Override
            public Void then(Task<Route> task) throws Exception {

                if (task.isFaulted()) {
                    Log.e("MainActivity", "Task Get failed " + task.getError());
                } else {
                    Log.e("MainActivity", "Task Get Successsss " + task.getResult());
                    accBmi160.stepDetector().start();
                    accBmi160.start();
                }
                return null;
            }
        });
    }

    public void retrieveBoard() {
        final BluetoothManager btManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        final BluetoothDevice remoteDevice = btManager.getAdapter().getRemoteDevice(MW_ADDRESS);

        // Create a MetaWear board object for the Bluetooth Device
        //board = serviceBinder.getMetaWearBoard(remoteDevice);
        //board = serviceBinder.getMetaWearBoard(remoteDevice);

        board.connectAsync().onSuccessTask(new Continuation<Void, Task<Route>>() {
            @Override
            public Task<Route> then(Task<Void> task) throws Exception {

                Log.e("MainActivity", "Connected");

                board.readDeviceInformationAsync()
                        .continueWith((Continuation<DeviceInformation, Void>) task1 -> {
                            Log.e("MainActivity", "Device Information: " + task1.getResult());

                            manufacturerValue.setText(task1.getResult().manufacturer);
                            modelNumberValue.setText(task1.getResult().modelNumber);
                            serialNumberValue.setText(task1.getResult().serialNumber);
                            firmwareRevisionValue.setText(task1.getResult().firmwareRevision);
                            hardwareRevisionValue.setText(task1.getResult().hardwareRevision);
                            deviceMacAddressValue.setText(board.getMacAddress());

                            return null;
                        }, Task.UI_THREAD_EXECUTOR);

                accBmi160 = board.getModule(AccelerometerBmi160.class);
                accBmi160.stepDetector().configure()
                        .mode(AccelerometerBmi160.StepDetectorMode.NORMAL)
                        .commit();
                return accBmi160.stepDetector().addRouteAsync(new RouteBuilder() {
                    @Override
                    public void configure(RouteComponent source) {
                        source.stream(new Subscriber() {
                            @Override
                            public void apply(Data data, Object... env) {
                                step += 1;

                                handler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        stepText.setText(String.valueOf(step));
                                    }
                                });

                                Log.e("MainActivity", "Took a step");
                            }
                        });
                    }
                });
            }
        }).continueWith(new Continuation<Route, Void>() {
            @Override
            public Void then(Task<Route> task) throws Exception {

                if (task.isFaulted()) {
                    Log.e("MainActivity", "Task Get failed " + task.getError());
                } else {
                    Log.e("MainActivity", "Task Get Successsss " + task.getResult());
                    accBmi160.stepDetector().start();
                    accBmi160.start();
                }
                return null;
            }
        });
/*
        board.connectAsync().onSuccessTask((Task<Void> task) -> {
//                if (task.isFaulted()) {
//                    Log.e("MainActivity", "Failed to connect");
//                } else {
//
////                    settings = board.getModule(Settings.class);
////                    settings.battery().addRouteAsync(new RouteBuilder() {
////                        @Override
////                        public void configure(RouteComponent source) {
////                            source.stream(new Subscriber() {
////                                @Override
////                                public void apply(Data data, Object... env) {
////                                    Log.e("MainActivity", "battery state = " + data.value(Settings.BatteryState.class));
////                                }
////                            });
////                        }
////                    }).continueWith(new Continuation<Route, Void>() {
////                        @Override
////                        public Void then(Task<Route> task) throws Exception {
////                            settings.battery().read();
////                            return null;
////                        }
////                    });
//                }
            Log.e("MainActivity", "Connected");

            board.readDeviceInformationAsync()
                    .continueWith((Continuation<DeviceInformation, Void>) task1 -> {
                        Log.e("MainActivity", "Device Information: " + task1.getResult());
                        return null;
                    });

            accelerometer = board.getModule(Accelerometer.class);
            accelerometer.configure()
                    .odr(25f)       // Set sampling frequency to 25Hz, or closest valid ODR
                    .range(4f)      // Set data range to +/-4g, or closet valid range
                    .commit();

            return accelerometer.acceleration().addRouteAsync(source ->
                    source.stream((Subscriber) (data, env) -> {
                        Log.e("MainActivity", data.value(Acceleration.class).toString());
                    }));
        }).continueWith((Continuation<Route, Void>) task -> {
            if (task.isFaulted()) {
                Log.e("MainActivity", "Task Get failed " + task.getError());
            } else {
                Log.e("MainActivity", "Task Get Successsss " + task.getResult());
            }
            return null;
        });

*/

/*        board.connectAsync().onSuccessTask((Task<Void> task) -> {

//            if (task.isFaulted()) {
//                Log.e("MainActivity", "Failed to connect");
//            } else {
//                Log.e("MainActivity", "Connected");
//            }

            Log.e("MainActivity", "Connected");

            accelerometer = board.getModule(Accelerometer.class);
            accelerometer.configure()
                    .odr(25f)       // Set sampling frequency to 25Hz, or closest valid ODR
                    .range(4f)      // Set data range to +/-4g, or closet valid range
                    .commit();
            return accelerometer.acceleration().addRouteAsync(new RouteBuilder() {
                @Override
                public void configure(RouteComponent source) {
                    source.stream(new Subscriber() {
                        @Override
                        public void apply(Data data, Object... env) {
                            Log.e("MainActivity", data.value(Acceleration.class).toString());
                        }
                    });
                }
            }).continueWith(new Continuation<Route, Void>() {
                @Override
                public Void then(Task<Route> task) throws Exception {
                    accelerometer.acceleration().start();
                    accelerometer.start();
                    return null;
                }
            });
        }*/

    }


    private Continuation<Void, Void> reconnectResult= task -> {
        ((DialogFragment) getSupportFragmentManager().findFragmentByTag(RECONNECT_DIALOG_TAG)).dismiss();
        Log.e(TAG, "Continuation:  in");
        if (task.isCancelled()) {
            Log.e(TAG, "Continuation:  task.isCancelled()");
            finish();
        } else {

            Log.e(TAG, "Continuation:  else");
            setConnInterval(board.getModule(Settings.class));
            setup();
            ((ModuleFragmentBase) currentFragment).reconnected();

        }

        return null;
    };

    private void attemptReconnect() {
        Log.e(TAG, "attemptReconnect:  -  호출 ");

//        accBmi160.stop();
//        accBmi160.stepDetector().stop();
//        accBmi160 = null;
        handler.post(new Runnable() {
            @Override
            public void run() {
                manufacturerValue.setText(" ");
                modelNumberValue.setText(" ");
                serialNumberValue.setText(" ");
                firmwareRevisionValue.setText(" ");
                hardwareRevisionValue.setText(" ");
                deviceMacAddressValue.setText(" ");
                board_battery_level_value.setText(" ");
                board_rssi_value.setText(" ");
            }
        });

        attemptReconnect(0);
    }

    private void attemptReconnect(long delay) {
        ReconnectDialogFragment dialogFragment = ReconnectDialogFragment.newInstance(btDevice);
        dialogFragment.show(getSupportFragmentManager(), RECONNECT_DIALOG_TAG);

        if (delay != 0) {
            taskScheduler.postDelayed(() -> MWScanActivity.reconnect(board).continueWith(reconnectResult), delay);
        } else {
            MWScanActivity.reconnect(board).continueWith(reconnectResult);
            Log.e(TAG, "attemptReconnect:  -  재연결 다이얼로그 ");
        }
    }
    @Override
    public BluetoothDevice getBtDevice() {
        return btDevice;
    }
    @Override
    public void resetConnectionStateHandler(long delay) {
        attemptReconnect(delay);
    }
    @Override
    public void initiateDfu(Object path) {
    }
    public static class ReconnectDialogFragment extends DialogFragment implements ServiceConnection {
        private static final String KEY_BLUETOOTH_DEVICE = "com.mbientlab.metawear.app.NavigationActivity.ReconnectDialogFragment.KEY_BLUETOOTH_DEVICE";

        private ProgressDialog reconnectDialog = null;
        private BluetoothDevice btDevice = null;
        private MetaWearBoard currentMwBoard = null;

        public static ReconnectDialogFragment newInstance(BluetoothDevice btDevice) {
            Bundle args = new Bundle();
            args.putParcelable(KEY_BLUETOOTH_DEVICE, btDevice);
            ReconnectDialogFragment newFragment = new ReconnectDialogFragment();
            newFragment.setArguments(args);
            return newFragment;
        }

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {

            btDevice = getArguments().getParcelable(KEY_BLUETOOTH_DEVICE);
            getActivity().getApplicationContext().bindService(new Intent(getActivity(), BtleService.class), this, BIND_AUTO_CREATE);

            reconnectDialog = new ProgressDialog(getActivity());
            reconnectDialog.setTitle(getString(R.string.title_reconnect_attempt));
            reconnectDialog.setMessage(getString(R.string.message_wait));
            reconnectDialog.setCancelable(false);
            reconnectDialog.setCanceledOnTouchOutside(false);
            reconnectDialog.setIndeterminate(true);
            reconnectDialog.setButton(DialogInterface.BUTTON_NEGATIVE, getString(R.string.label_cancel), (dialogInterface, i) -> {
                currentMwBoard.disconnectAsync();
                getActivity().finish();
            });
            return reconnectDialog;
        }
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            currentMwBoard = ((BtleService.LocalBinder) service).getMetaWearBoard(btDevice);
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
        }
    }
}

