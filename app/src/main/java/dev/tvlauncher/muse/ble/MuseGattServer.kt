package dev.tvlauncher.muse.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import dev.tvlauncher.muse.pairing.SetupTransport
import dev.tvlauncher.muse.wire.BleChunkAssembler
import dev.tvlauncher.muse.wire.BleFraming
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.UUID

/**
 * The TV as a Muse gadget over Bluetooth LE (the SDK's ble_server.py; spec §2.1–2.3).
 * One GATT service: the phone writes commands to RX and the TV notifies replies on TX.
 * While open, the adapter is renamed to the gadget name (the phone reads the GAP name as
 * well as the advertised one) and advertises the service; [stop] puts the name back.
 *
 * Everything runs on one background thread. Notifications go out one at a time, each
 * after the previous one's onNotificationSent plus the firmware's 50 ms stagger.
 */
@SuppressLint("MissingPermission") // BLUETOOTH and BLUETOOTH_ADMIN are install-time grants on the TV (API 30)
class MuseGattServer(
    private val context: Context,
    private val bleName: String,
    private val listener: Listener,
) : SetupTransport {
    /** Called on the server's thread. */
    interface Listener {
        /** Advertising started, or couldn't ([error] says why). */
        fun onOpened(error: String?)

        /** A phone started talking to us. */
        fun onConnected()

        /** One reassembled command from the phone. */
        fun onMessage(message: ByteArray)

        /** The phone that was talking to us went away. */
        fun onDisconnected()
    }

    private val thread = HandlerThread("muse-ble").apply { start() }
    private val handler = Handler(thread.looper)
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = manager?.adapter
    private var server: BluetoothGattServer? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var advertising = false
    private var renamed = false
    private var stopped = false

    /** The phone we answer: whoever wrote last. */
    private var client: BluetoothDevice? = null
    private val mtus = HashMap<String, Int>()
    private val subscriptions = HashMap<String, ByteArray>()
    private val prepared = HashMap<String, ByteArrayOutputStream>()
    private val assembler = BleChunkAssembler()
    private val outbox = ArrayDeque<ByteArray>()
    private var inFlight = false
    private var lastNotified = ByteArray(0)
    private val pumpRunnable = Runnable { pump() }
    private val notifyWatchdog = Runnable {
        Log.w(TAG, "no onNotificationSent; continuing")
        inFlight = false
        pump()
    }

    fun start() {
        handler.post {
            val adapter = adapter
            if (adapter == null || !adapter.isEnabled) return@post listener.onOpened("Bluetooth is off")
            if (adapter.bluetoothLeAdvertiser == null) return@post listener.onOpened("this TV can't advertise over Bluetooth LE")
            val gatt = manager?.openGattServer(context, callback)
                ?: return@post listener.onOpened("couldn't open the Bluetooth GATT server")
            server = gatt
            val rx = BluetoothGattCharacteristic(
                RX_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
            val tx = BluetoothGattCharacteristic(
                TX_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ,
            ).apply {
                addDescriptor(
                    BluetoothGattDescriptor(
                        CCCD_UUID,
                        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                    ),
                )
            }
            this.tx = tx
            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
                addCharacteristic(rx)
                addCharacteristic(tx)
            }
            if (!gatt.addService(service)) listener.onOpened("couldn't add the Muse GATT service")
            // Continues in onServiceAdded.
        }
    }

    /** Stops advertising, drops the phone and restores the adapter name. Safe to call twice. */
    fun stop() {
        handler.post {
            if (stopped) return@post
            stopped = true
            handler.removeCallbacksAndMessages(null)
            if (advertising) {
                runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
                advertising = false
            }
            server?.let { gatt ->
                client?.let { runCatching { gatt.cancelConnection(it) } }
                runCatching { gatt.clearServices() }
                runCatching { gatt.close() }
            }
            server = null
            client = null
            outbox.clear()
            if (renamed) restoreName(context)
            thread.quitSafely()
        }
    }

    override fun send(message: ByteArray, framed: Boolean) {
        handler.post {
            val device = client ?: return@post
            val mtu = mtus[device.address] ?: BleFraming.DEFAULT_ATT_MTU
            outbox.addAll(if (framed) BleFraming.encodeChunks(message, mtu) else listOf(message))
            pump()
        }
    }

    override fun disconnect(delayMillis: Long) {
        handler.postDelayed({ client?.let { server?.cancelConnection(it) } }, delayMillis)
    }

    private fun renameThenAdvertise() {
        val adapter = adapter ?: return
        rememberName(context, adapter)
        renamed = adapter.setName(bleName)
        if (!renamed) Log.w(TAG, "couldn't rename the Bluetooth adapter")
        waitForName(SystemClock.uptimeMillis() + RENAME_TIMEOUT_MS)
    }

    /** setName applies asynchronously; advertise once it has (or give up waiting). */
    private fun waitForName(deadline: Long) {
        if (stopped) return
        if (adapter?.name == bleName || SystemClock.uptimeMillis() > deadline) {
            advertise()
        } else {
            handler.postDelayed({ waitForName(deadline) }, 100)
        }
    }

    private fun advertise() {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return listener.onOpened("Bluetooth LE advertising is unavailable")
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // Service UUID + "unpaired" flag in the ad, the name in the scan response, so both
        // fit the 31-byte legacy limit (as the ESP32 firmware lays them out).
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addManufacturerData(PAIRED_FLAG_COMPANY_ID, byteArrayOf(0))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            handler.post {
                advertising = true
                Log.i(TAG, "advertising as $bleName")
                listener.onOpened(null)
            }
        }

        override fun onStartFailure(errorCode: Int) {
            handler.post { listener.onOpened("Bluetooth advertising failed (error $errorCode)") }
        }
    }

    private fun deliver(device: BluetoothDevice, packet: ByteArray) {
        if (client != device) {
            // A different phone took over: whatever the last one started is void.
            if (client != null) dropClient()
            client = device
            Log.i(TAG, "phone connected")
            listener.onConnected()
        }
        assembler.feed(packet)?.let(listener::onMessage)
    }

    private fun dropClient() {
        client = null
        assembler.reset()
        outbox.clear()
        inFlight = false
        handler.removeCallbacks(notifyWatchdog)
        listener.onDisconnected()
    }

    private fun pump() {
        if (inFlight || stopped) return
        val device = client ?: return outbox.clear()
        val packet = outbox.pollFirst() ?: return
        lastNotified = packet
        if (notify(device, packet)) {
            inFlight = true
            handler.postDelayed(notifyWatchdog, NOTIFY_TIMEOUT_MS)
        } else {
            Log.w(TAG, "notification not sent (${packet.size} bytes)")
            handler.postDelayed(pumpRunnable, BleFraming.CHUNK_STAGGER_MS)
        }
    }

    @Suppress("DEPRECATION")
    private fun notify(device: BluetoothDevice, packet: ByteArray): Boolean {
        val gatt = server ?: return false
        val tx = tx ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.notifyCharacteristicChanged(device, tx, false, packet) == BluetoothStatusCodes.SUCCESS
        } else {
            tx.value = packet
            gatt.notifyCharacteristicChanged(device, tx, false)
        }
    }

    private fun respond(device: BluetoothDevice, requestId: Int, status: Int, offset: Int = 0, value: ByteArray? = null) {
        runCatching { server?.sendResponse(device, requestId, status, offset, value) }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            handler.post {
                if (stopped) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) renameThenAdvertise() else listener.onOpened("GATT service error $status")
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            handler.post {
                if (stopped) return@post
                // Fires for every LE link, the remote's included, so a phone only counts once it writes.
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    mtus.remove(device.address)
                    subscriptions.remove(device.address)
                    prepared.remove(device.address)
                    if (device == client) {
                        Log.i(TAG, "phone disconnected")
                        dropClient()
                    }
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            handler.post {
                mtus[device.address] = mtu
                Log.i(TAG, "ATT MTU $mtu")
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val data = value ?: ByteArray(0)
            handler.post {
                if (stopped) return@post
                if (characteristic.uuid != RX_UUID) {
                    if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED)
                    return@post
                }
                if (preparedWrite) {
                    // A long write: buffer by offset until onExecuteWrite.
                    val buffer = prepared.getOrPut(device.address) { ByteArrayOutputStream() }
                    if (offset != buffer.size() || buffer.size() + data.size > BleFraming.MAX_MESSAGE_BYTES) {
                        prepared.remove(device.address)
                        if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET)
                        return@post
                    }
                    buffer.write(data)
                    if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, data)
                    return@post
                }
                if (offset != 0) {
                    if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET)
                    return@post
                }
                if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS)
                deliver(device, data)
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            handler.post {
                val buffer = prepared.remove(device.address)
                respond(device, requestId, BluetoothGatt.GATT_SUCCESS)
                if (execute && buffer != null && !stopped) deliver(device, buffer.toByteArray())
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            handler.post {
                // Reading TX returns the last notified value.
                val value = lastNotified
                when {
                    characteristic.uuid != TX_UUID -> respond(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED)
                    offset > value.size -> respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET)
                    else -> respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
                }
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            handler.post {
                val value = subscriptions[device.address] ?: BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            handler.post {
                if (descriptor.uuid == CCCD_UUID && value != null) subscriptions[device.address] = value
                if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            handler.post {
                handler.removeCallbacks(notifyWatchdog)
                inFlight = false
                handler.postDelayed(pumpRunnable, BleFraming.CHUNK_STAGGER_MS)
            }
        }
    }

    companion object {
        private const val TAG = "MuseBle"
        val SERVICE_UUID: UUID = UUID.fromString("7fdd3d1c-38ea-46cf-8b46-314ecf5f240c")
        val RX_UUID: UUID = UUID.fromString("4d593029-28a2-4a6e-a1f0-3c2d5e8f9b01")
        val TX_UUID: UUID = UUID.fromString("d75dc4ca-7b2b-4e9c-8f0a-1d2e3f4a5b6c")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val PAIRED_FLAG_COMPANY_ID = 0xFFFF
        private const val RENAME_TIMEOUT_MS = 3_000L
        private const val NOTIFY_TIMEOUT_MS = 1_000L
        private const val PREFS = "muse_ble"
        private const val KEY_ORIGINAL_NAME = "original_name"

        /**
         * Saves the adapter's real name before renaming it. If the launcher died mid-pairing
         * the adapter still has the gadget name, so the name saved then is put back first.
         */
        private fun rememberName(context: Context, adapter: BluetoothAdapter) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val saved = prefs.getString(KEY_ORIGINAL_NAME, null)
            val current = adapter.name
            if (saved == null || current?.startsWith(GADGET_PREFIX) != true) {
                prefs.edit().putString(KEY_ORIGINAL_NAME, current).commit()
            }
        }

        /** Puts the adapter's real name back after a pairing window (or a crashed one). */
        fun restoreName(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val saved = prefs.getString(KEY_ORIGINAL_NAME, null) ?: return
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
            // Keep the saved name for next time if Bluetooth is off and the rename can't happen now.
            if (adapter.name?.startsWith(GADGET_PREFIX) == true && !adapter.setName(saved)) return
            prefs.edit().remove(KEY_ORIGINAL_NAME).apply()
        }

        private const val GADGET_PREFIX = "MuseGadget"
    }
}
