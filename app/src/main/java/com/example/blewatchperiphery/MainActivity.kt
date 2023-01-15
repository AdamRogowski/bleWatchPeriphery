package com.example.blewatchperiphery

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import java.text.SimpleDateFormat
import java.util.*

private const val ENABLE_BLUETOOTH_REQUEST_CODE = 1
private const val BLUETOOTH_ALL_PERMISSIONS_REQUEST_CODE = 2
private const val SERVICE_UUID = "25AE1441-05D3-4C5B-8281-93D4E07420CF"
private const val CHAR_FOR_INDICATE_UUID = "25AE1444-05D3-4C5B-8281-93D4E07420CF"
private const val CCC_DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805f9b34fb"

class MainActivity : AppCompatActivity(), SensorEventListener {

    private val switchAdvertising: SwitchCompat
        get() = findViewById<SwitchMaterial>(R.id.switchAdvertising)
    private val textViewLog: TextView
        get() = findViewById(R.id.textViewLog)
    private val scrollViewLog: ScrollView
        get() = findViewById(R.id.scrollViewLog)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Keep the Wear screen always on (for testing only!)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        appendLog("MainActivity.onCreate")

        switchAdvertising.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                //appendLog("ON")
                prepareSensorListener()
                prepareAndStartAdvertising()
            } else {
                //appendLog("OFF")
                bleStopAdvertising()
            }
        }
    }

    override fun onDestroy() {
        bleStopAdvertising()
        super.onDestroy()
    }


    //Toolkit---------------------------------------------------------------------------------------------
    private fun updateSubscribers() {
        appendLog("Currently subscribers: ${subscribedDevices.count()}")
    }

    private fun anyoneSubscribes(): Boolean{
        return subscribedDevices.isNotEmpty()
    }

    fun onTapTest(view: View) {
        val data = "[${getCurrentTime()}] test"
        if(anyoneSubscribes()){
            bleIndicate(data)
            appendLog("indication test sent")
        }
        else{
            appendLog("No one subscribes")
        }
    }

    @SuppressLint("SetTextI18n")
    fun onTapClearLog(view: View) {
        textViewLog.text = "Logs:"
        appendLog("log cleared")
    }

    private fun getCurrentTime(): String{
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    @SuppressLint("SetTextI18n")
    private fun appendLog(message: String) {
        Log.d("appendLog", message)
        runOnUiThread {
            textViewLog.text = textViewLog.text.toString() + "\n${getCurrentTime()} $message"

            // wait for the textView to update
            Handler(Looper.getMainLooper()).postDelayed({
                scrollViewLog.fullScroll(View.FOCUS_DOWN)
            }, 20)
        }
    }
    //-----------------------------------------------------------------------------------------------------------

    //BLE GATT server--------------------------------------------------------------------------------------------
    private var gattServer: BluetoothGattServer? = null
    private val charForIndicate get() = gattServer?.getService(UUID.fromString(SERVICE_UUID))?.getCharacteristic(UUID.fromString(CHAR_FOR_INDICATE_UUID))
    private val subscribedDevices = mutableSetOf<BluetoothDevice>()


    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            runOnUiThread {
                if (newState == BluetoothProfile.STATE_CONNECTED){
                    appendLog("Central device did connect")
                    updateSubscribers()
                }
                else {
                    appendLog("Central device did disconnect")
                    subscribedDevices.remove(device)
                    updateSubscribers()
                }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            appendLog("onNotificationSent status=$status")
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            var log = "onDescriptorReadRequest"
            if (descriptor.uuid == UUID.fromString(CCC_DESCRIPTOR_UUID)) {
                val returnValue = if (subscribedDevices.contains(device)) {
                    log += " CCCD response=ENABLE_NOTIFICATION"
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    log += " CCCD response=DISABLE_NOTIFICATION"
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, returnValue)
            } else {
                log += " unknown uuid=${descriptor.uuid}"
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
            appendLog(log)
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            var strLog = "onDescriptorWriteRequest"
            if (descriptor.uuid == UUID.fromString(CCC_DESCRIPTOR_UUID)) {
                var status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                if (descriptor.characteristic.uuid == UUID.fromString(CHAR_FOR_INDICATE_UUID)) {
                    if (Arrays.equals(value, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)) {
                        subscribedDevices.add(device)
                        status = BluetoothGatt.GATT_SUCCESS
                        strLog += ", subscribed"
                    } else if (Arrays.equals(value, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                        subscribedDevices.remove(device)
                        status = BluetoothGatt.GATT_SUCCESS
                        strLog += ", unsubscribed"
                    }
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, status, 0, null)
                }
                updateSubscribers()
            } else {
                strLog += " unknown uuid=${descriptor.uuid}"
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
            }
            appendLog(strLog)
        }
    }
    //-----------------------------------------------------------------------------------------------------------


    //BLE advertising--------------------------------------------------------------------------------------------
    private val bluetoothManager: BluetoothManager by lazy {
        getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        bluetoothManager.adapter
    }

    private val bleAdvertiser by lazy {
        bluetoothAdapter.bluetoothLeAdvertiser
    }

    private fun prepareAndStartAdvertising() {
        ensureBluetoothCanBeUsed { isSuccess, message ->
            runOnUiThread {
                appendLog(message)
                if (isSuccess) bleStartAdvertising()
            }
        }
    }

    private fun bleStartAdvertising() {
        bleStartGattServer()
        bleAdvertiser.startAdvertising(advertiseSettings, advertiseData, advertiseCallback)
    }

    private fun bleStopAdvertising() {
        bleStopGattServer()
        bleAdvertiser.stopAdvertising(advertiseCallback)
    }

    private fun bleStartGattServer() {
        val gattServer = bluetoothManager.openGattServer(this, gattServerCallback)
        val service = BluetoothGattService(UUID.fromString(SERVICE_UUID), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        var charForIndicate = BluetoothGattCharacteristic(UUID.fromString(CHAR_FOR_INDICATE_UUID),
            BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_READ)
        var charConfigDescriptor = BluetoothGattDescriptor(UUID.fromString(CCC_DESCRIPTOR_UUID),
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)
        charForIndicate.addDescriptor(charConfigDescriptor)

        service.addCharacteristic(charForIndicate)

        val result = gattServer.addService(service)
        this.gattServer = gattServer

        appendLog("addService " + when(result) {
            true -> "OK"
            false -> "fail"
        })
    }

    private fun bleStopGattServer() {
        gattServer?.close()
        gattServer = null
        appendLog("gattServer stopped")
    }

    private fun bleIndicate(data: String) {
        charForIndicate?.let {
            it.value = data.toByteArray(Charsets.UTF_8)
            for (device in subscribedDevices) {
                gattServer?.notifyCharacteristicChanged(device, it, true)
            }
        }
    }

    private val advertiseSettings = AdvertiseSettings.Builder()
        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
        .setConnectable(true)
        .build()

    private val advertiseData = AdvertiseData.Builder()
        .setIncludeDeviceName(false) // don't include name, because if name size > 8 bytes, ADVERTISE_FAILED_DATA_TOO_LARGE
        .addServiceUuid(ParcelUuid(UUID.fromString(SERVICE_UUID)))
        .build()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            appendLog("Advertise start success\n$SERVICE_UUID")
        }

        override fun onStartFailure(errorCode: Int) {
            val desc = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "\nADVERTISE_FAILED_DATA_TOO_LARGE"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "\nADVERTISE_FAILED_TOO_MANY_ADVERTISERS"
                ADVERTISE_FAILED_ALREADY_STARTED -> "\nADVERTISE_FAILED_ALREADY_STARTED"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "\nADVERTISE_FAILED_INTERNAL_ERROR"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "\nADVERTISE_FAILED_FEATURE_UNSUPPORTED"
                else -> ""
            }
            appendLog("Advertise start failed: errorCode=$errorCode $desc")
        }
    }
    //-----------------------------------------------------------------------------------------------------------


    //Heart Rate sensor------------------------------------------------------------------------------------------
    private fun registerSensor(){
        val sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        val mHeartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        if (mHeartRateSensor == null) {
            appendLog("Heart rate sensor not available")
        }
        sensorManager.registerListener(this, mHeartRateSensor, SensorManager.SENSOR_DELAY_NORMAL)
        appendLog("HRsensor listener registered")
    }

    private fun prepareSensorListener(){
        if (checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BODY_SENSORS), 1)
        } else {
            appendLog("Sensor access permitted")
        }
        runOnUiThread {
            registerSensor()
        }
    }

    override fun onSensorChanged(event: SensorEvent){
        if(event.sensor.type == Sensor.TYPE_HEART_RATE) {
            val message = "hr:" + event.values[0].toInt()
            //appendLog(message)
            if(anyoneSubscribes()) bleIndicate(message)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        appendLog( "onAccuracyChanged - accuracy: $accuracy")
    }
    //-----------------------------------------------------------------------------------------------------------


    //Permissions and Settings-----------------------------------------------------------------------------------
    enum class AskType {
        AskOnce,
        InsistUntilSuccess
    }

    private var activityResultHandlers = mutableMapOf<Int, (Int) -> Unit>()
    private var permissionResultHandlers = mutableMapOf<Int, (Array<out String>, IntArray) -> Unit>()

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        activityResultHandlers[requestCode]?.let { handler ->
            handler(resultCode)
        } ?: run {
            appendLog("Error: onActivityResult requestCode=$requestCode result=$resultCode not handled")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionResultHandlers[requestCode]?.let { handler ->
            handler(permissions, grantResults)
        } ?: run {
            appendLog("Error: onRequestPermissionsResult requestCode=$requestCode not handled")
        }
    }

    private fun ensureBluetoothCanBeUsed(completion: (Boolean, String) -> Unit) {
        grantBluetoothPeripheralPermissions(AskType.AskOnce) { isGranted ->
            if (!isGranted) {
                completion(false, "Bluetooth permissions denied")
                return@grantBluetoothPeripheralPermissions
            }

            enableBluetooth(AskType.AskOnce) { isEnabled ->
                if (!isEnabled) {
                    completion(false, "Bluetooth OFF")
                    return@enableBluetooth
                }
                completion(true, "BLE ready for use")
            }
        }
    }

    private fun enableBluetooth(askType: AskType, completion: (Boolean) -> Unit) {
        if (bluetoothAdapter.isEnabled) {
            completion(true)
        } else {
            val intentString = BluetoothAdapter.ACTION_REQUEST_ENABLE
            val requestCode = ENABLE_BLUETOOTH_REQUEST_CODE

            // set activity result handler
            activityResultHandlers[requestCode] = { result ->
                val isSuccess = result == Activity.RESULT_OK
                if (isSuccess || askType != AskType.InsistUntilSuccess) {
                    activityResultHandlers.remove(requestCode)
                    completion(isSuccess)
                } else {
                    // start activity for the request again
                    startActivityForResult(Intent(intentString), requestCode)
                }
            }
            // start activity for the request
            startActivityForResult(Intent(intentString), requestCode)
        }
    }

    private fun grantBluetoothPeripheralPermissions(askType: AskType, completion: (Boolean) -> Unit) {
        val wantedPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
        } else {
            emptyArray()
        }

        if (wantedPermissions.isEmpty() || hasPermissions(wantedPermissions)) {
            completion(true)
        } else {
            runOnUiThread {
                val requestCode = BLUETOOTH_ALL_PERMISSIONS_REQUEST_CODE

                // set permission result handler
                permissionResultHandlers[requestCode] = { _, grantResults ->
                    val isSuccess = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                    if (isSuccess || askType != AskType.InsistUntilSuccess) {
                        permissionResultHandlers.remove(requestCode)
                        completion(isSuccess)
                    } else {
                        // request again
                        requestPermissionArray(wantedPermissions, requestCode)
                    }
                }

                requestPermissionArray(wantedPermissions, requestCode)
            }
        }
    }

    private fun Context.hasPermissions(permissions: Array<String>): Boolean = permissions.all {
        ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun Activity.requestPermissionArray(permissions: Array<String>, requestCode: Int) {
        ActivityCompat.requestPermissions(this, permissions, requestCode)
    }
    //-----------------------------------------------------------------------------------------------------------
}