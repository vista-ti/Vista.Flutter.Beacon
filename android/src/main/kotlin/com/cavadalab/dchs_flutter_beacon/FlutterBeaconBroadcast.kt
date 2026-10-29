package com.cavadalab.dchs_flutter_beacon

import android.Manifest
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import io.flutter.plugin.common.MethodChannel
import org.altbeacon.beacon.Beacon
import org.altbeacon.beacon.BeaconParser
import org.altbeacon.beacon.BeaconTransmitter

/** One advertisement attempt; [stop] affects only this attempt. */
internal interface Advertisement {
    val isStarted: Boolean

    /** Starts advertising; [onResult] receives null on success or an AdvertiseCallback error code. */
    fun start(beacon: Beacon, advertiseMode: Int?, txPowerLevel: Int?, onResult: (Int?) -> Unit)

    fun stop()
}

internal fun interface BeaconAdvertiser {
    fun create(): Advertisement
}

private class AltBeaconAdvertisement(
    private val context: Context,
    private val iBeaconLayout: BeaconParser,
) : Advertisement {
    companion object {
        private val TAG = AltBeaconAdvertisement::class.java.simpleName
    }

    private var transmitter: BeaconTransmitter? = null

    override val isStarted: Boolean
        get() = transmitter?.isStarted == true

    override fun start(beacon: Beacon, advertiseMode: Int?, txPowerLevel: Int?, onResult: (Int?) -> Unit) {
        try {
            // BeaconTransmitter fetches BluetoothLeAdvertiser once in its constructor and never
            // refreshes it. A fresh instance per attempt picks up Bluetooth enabled after launch.
            val newTransmitter = BeaconTransmitter(context, iBeaconLayout)
            transmitter = newTransmitter
            advertiseMode?.let { newTransmitter.advertiseMode = it }
            txPowerLevel?.let { newTransmitter.advertiseTxPowerLevel = it }
            newTransmitter.startAdvertising(beacon, object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    Log.d(TAG, "Start broadcasting = $beacon")
                    onResult(null)
                }

                override fun onStartFailure(errorCode: Int) {
                    Log.e(TAG, "Start broadcasting failed: $errorCode")
                    onResult(errorCode)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start advertising", e)
            onResult(AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR)
        }
    }

    override fun stop() {
        transmitter?.stopAdvertising()
    }
}

/**
 * Owns the beacon advertisement of one Flutter engine.
 *
 * It lives as long as the engine, not the Activity, so the advertisement stays controllable
 * across Activity recreation, and [dispose] stops it when the engine goes away.
 */
class FlutterBeaconBroadcast internal constructor(
    private val advertiser: BeaconAdvertiser,
    private val needsAdvertisePermission: () -> Boolean,
    private val schedule: (Long, () -> Unit) -> () -> Unit,
) {
    constructor(context: Context, iBeaconLayout: BeaconParser) : this(
        BeaconAdvertiser { AltBeaconAdvertisement(context.applicationContext, iBeaconLayout) },
        needsAdvertisePermission = {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) !=
                PackageManager.PERMISSION_GRANTED
        },
        schedule = { delayMs, action ->
            val handler = Handler(Looper.getMainLooper())
            val runnable = Runnable(action)
            handler.postDelayed(runnable, delayMs)
            val cancel: () -> Unit = { handler.removeCallbacks(runnable) }
            cancel
        },
    )

    companion object {
        const val REQUEST_CODE_BLUETOOTH_ADVERTISE = 1236
        const val START_TIMEOUT_MS = 10_000L
    }

    private class PendingStart(val beacon: Beacon, val advertiseMode: Int?, val txPowerLevel: Int?)

    /** Requests BLUETOOTH_ADVERTISE from the attached Activity; null while no Activity is attached. */
    var permissionRequester: (() -> Unit)? = null

    private var advertisement: Advertisement? = null
    private var pendingResult: MethodChannel.Result? = null
    private var pendingStart: PendingStart? = null
    private var awaitingPermission = false
    private var cancelTimeout: (() -> Unit)? = null
    private var attempt = 0

    fun isBroadcasting(result: MethodChannel.Result) {
        result.success(advertisement?.isStarted == true)
    }

    fun stopBroadcast(result: MethodChannel.Result) {
        finishWithError("Broadcast was stopped before it started")
        advertisement?.stop()
        result.success(true)
    }

    fun startBroadcast(arguments: Any?, result: MethodChannel.Result) {
        if (arguments !is Map<*, *>) {
            result.error("Broadcast", "Invalid parameter", null)
            return
        }
        if (pendingResult != null) {
            result.error("Broadcast", "ALREADY_STARTING", null)
            return
        }

        pendingResult = result
        pendingStart = PendingStart(
            FlutterBeaconUtils.beaconFromMap(arguments),
            arguments["advertisingMode"] as? Int,
            arguments["advertisingTxPowerLevel"] as? Int,
        )

        if (needsAdvertisePermission()) {
            val requestPermission = permissionRequester
            if (requestPermission == null) {
                finishWithError("BLUETOOTH_ADVERTISE permission is required and no Activity is attached to request it")
                return
            }
            awaitingPermission = true
            requestPermission()
            return
        }

        startAdvertising()
    }

    fun onAdvertisePermissionResult(granted: Boolean) {
        if (!awaitingPermission) return
        awaitingPermission = false
        if (granted) {
            startAdvertising()
        } else {
            finishWithError("BLUETOOTH_ADVERTISE permission denied")
        }
    }

    /** The Activity can no longer show the permission prompt or deliver its result. */
    fun onActivityDetached() {
        permissionRequester = null
        if (awaitingPermission) {
            finishWithError("Activity was detached while requesting BLUETOOTH_ADVERTISE")
        }
    }

    /** The engine is going away: nothing could stop the advertisement afterwards. */
    fun dispose() {
        permissionRequester = null
        finishWithError("Flutter engine was detached before broadcasting started")
        advertisement?.stop()
    }

    private fun startAdvertising() {
        val start = pendingStart ?: return
        advertisement?.stop()

        val current = ++attempt
        val newAdvertisement = advertiser.create()
        advertisement = newAdvertisement
        cancelTimeout = schedule(START_TIMEOUT_MS) {
            if (current == attempt && pendingResult != null) {
                newAdvertisement.stop()
                finishWithError("START_TIMEOUT")
            }
        }

        newAdvertisement.start(start.beacon, start.advertiseMode, start.txPowerLevel) { errorCode ->
            if (current != attempt || pendingResult == null) {
                // Answered after timeout, stop, restart or dispose: never leave it advertising unowned.
                if (errorCode == null) newAdvertisement.stop()
                return@start
            }
            if (errorCode == null) {
                finishWithSuccess()
            } else {
                finishWithError(advertiseErrorName(errorCode))
            }
        }
    }

    private fun advertiseErrorName(errorCode: Int): String = when (errorCode) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
        else -> "FEATURE_UNSUPPORTED"
    }

    private fun finishWithSuccess() {
        takePendingResult()?.success(true)
    }

    private fun finishWithError(message: String) {
        takePendingResult()?.error("Broadcast", message, null)
    }

    private fun takePendingResult(): MethodChannel.Result? {
        cancelTimeout?.invoke()
        cancelTimeout = null
        awaitingPermission = false
        pendingStart = null
        val result = pendingResult
        pendingResult = null
        return result
    }
}
