package com.cavadalab.dchs_flutter_beacon

import io.flutter.plugin.common.MethodChannel
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.altbeacon.beacon.Beacon

internal class FlutterBeaconBroadcastTest {

    private class FakeAdvertisement(private val owner: FakeAdvertiser) : Advertisement {
        override var isStarted = false
        var callback: ((Int?) -> Unit)? = null

        override fun start(beacon: Beacon, advertiseMode: Int?, txPowerLevel: Int?, onResult: (Int?) -> Unit) {
            owner.startCalls++
            owner.lastBeacon = beacon
            owner.lastMode = advertiseMode
            callback = { errorCode ->
                if (errorCode == null) isStarted = true
                onResult(errorCode)
            }
        }

        override fun stop() {
            if (isStarted) owner.stopCalls++
            isStarted = false
        }
    }

    private class FakeAdvertiser : BeaconAdvertiser {
        val created = mutableListOf<FakeAdvertisement>()
        var startCalls = 0
        var stopCalls = 0
        var lastBeacon: Beacon? = null
        var lastMode: Int? = null

        override fun create() = FakeAdvertisement(this).also { created += it }

        /** True when any attempt is still on air, including orphans. */
        val isStarted get() = created.any { it.isStarted }
        val liveCount get() = created.count { it.isStarted }

        fun succeed(index: Int = created.lastIndex) = created[index].callback!!.invoke(null)
        fun fail(code: Int) = created.last().callback!!.invoke(code)
    }

    /** Throws on a second reply, like Flutter's DartMessenger reply. */
    private class RecordingResult : MethodChannel.Result {
        var successValue: Any? = null
        var succeeded = false
        var errorMessage: String? = null
        var calls = 0

        private fun reply() {
            check(calls == 0) { "Reply already submitted" }
            calls++
        }

        override fun success(result: Any?) {
            reply()
            succeeded = true
            successValue = result
        }

        override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
            reply()
            this.errorMessage = errorMessage
        }

        override fun notImplemented() {
            reply()
        }
    }

    private lateinit var advertiser: FakeAdvertiser
    private var needsPermission = false
    private var supported = true
    private var timeoutAction: (() -> Unit)? = null
    private var timeoutCancelled = false
    private var permissionRequests = 0
    private var bluetoothOff: (() -> Unit)? = null
    private var bluetoothWatchStopped = false
    private val states = mutableListOf<Boolean>()
    private lateinit var broadcast: FlutterBeaconBroadcast

    private val args = mapOf<String, Any?>(
        "proximityUUID" to "CB10023F-A318-3394-4199-A8730C7C1AEC",
        "major" to 1,
        "minor" to 2,
        "advertisingMode" to 2,
    )

    @BeforeTest
    fun setUp() {
        advertiser = FakeAdvertiser()
        needsPermission = false
        supported = true
        timeoutAction = null
        timeoutCancelled = false
        permissionRequests = 0
        bluetoothOff = null
        bluetoothWatchStopped = false
        states.clear()
        broadcast = FlutterBeaconBroadcast(
            advertiser,
            needsAdvertisePermission = { needsPermission },
            transmissionError = { if (supported) null else "BROADCAST_NOT_SUPPORTED" },
            schedule = { _, action ->
                timeoutAction = action
                val cancel: () -> Unit = { timeoutCancelled = true }
                cancel
            },
            watchBluetoothOff = { onOff ->
                bluetoothOff = onOff
                val stop: () -> Unit = { bluetoothWatchStopped = true }
                stop
            },
        )
        broadcast.permissionRequester = { permissionRequests++ }
        broadcast.stateListener = { states += it }
    }

    // Existing behavior

    @Test
    fun start_completesWithTrueWhenRadioConfirms() {
        val result = RecordingResult()
        broadcast.startBroadcast(args, result)

        assertEquals(0, result.calls, "must wait for the radio")
        advertiser.succeed()

        assertTrue(result.succeeded)
        assertEquals(true, result.successValue)
        assertEquals(2, advertiser.lastMode)
        assertTrue(timeoutCancelled)
    }

    @Test
    fun start_reportsRadioFailureCode() {
        val result = RecordingResult()
        broadcast.startBroadcast(args, result)
        advertiser.fail(1)

        assertEquals("DATA_TOO_LARGE", result.errorMessage)
    }

    @Test
    fun start_rejectsNonMapArguments() {
        val result = RecordingResult()
        broadcast.startBroadcast("invalid", result)

        assertEquals("Invalid parameter", result.errorMessage)
        assertEquals(0, advertiser.startCalls)
    }

    @Test
    fun stopAndIsBroadcasting_reflectAdvertiserState() {
        broadcast.startBroadcast(args, RecordingResult())
        advertiser.succeed()

        val isBroadcasting = RecordingResult()
        broadcast.isBroadcasting(isBroadcasting)
        assertEquals(true, isBroadcasting.successValue)

        val stop = RecordingResult()
        broadcast.stopBroadcast(stop)
        assertEquals(true, stop.successValue)
        assertFalse(advertiser.isStarted)
    }

    @Test
    fun permission_requestedThenStartsWhenGranted() {
        needsPermission = true
        val result = RecordingResult()
        broadcast.startBroadcast(args, result)

        assertEquals(1, permissionRequests)
        assertEquals(0, advertiser.startCalls)

        broadcast.onAdvertisePermissionResult(granted = true)
        advertiser.succeed()
        assertTrue(result.succeeded)
    }

    @Test
    fun permission_deniedReportsError() {
        needsPermission = true
        val result = RecordingResult()
        broadcast.startBroadcast(args, result)
        broadcast.onAdvertisePermissionResult(granted = false)

        assertEquals("BLUETOOTH_ADVERTISE permission denied", result.errorMessage)
        assertEquals(0, advertiser.startCalls)
    }

    // Gap #2: engine detach must not leave an orphaned advertisement

    @Test
    fun dispose_stopsActiveAdvertisement() {
        broadcast.startBroadcast(args, RecordingResult())
        advertiser.succeed()

        broadcast.dispose()

        assertFalse(advertiser.isStarted)
    }

    @Test
    fun dispose_failsPendingStartAndStopsLateSuccess() {
        val result = RecordingResult()
        broadcast.startBroadcast(args, result)

        broadcast.dispose()
        assertEquals(1, result.calls)
        assertTrue(result.errorMessage!!.contains("engine"))

        advertiser.succeed()
        assertFalse(advertiser.isStarted, "late success after dispose must be stopped")
        assertEquals(1, result.calls)
    }

    // Gap #5: activity detached while the permission prompt is open

    @Test
    fun activityDetached_failsStartAwaitingPermission() {
        needsPermission = true
        val result = RecordingResult()
        broadcast.startBroadcast(args, result)

        broadcast.onActivityDetached()

        assertEquals(1, result.calls)
        assertTrue(result.errorMessage!!.contains("Activity"))
        assertNull(broadcast.permissionRequester)
    }

    @Test
    fun permissionNeededWithoutActivity_failsImmediately() {
        needsPermission = true
        broadcast.permissionRequester = null
        val result = RecordingResult()
        broadcast.startBroadcast(args, result)

        assertEquals(1, result.calls)
        assertTrue(result.errorMessage!!.contains("BLUETOOTH_ADVERTISE"))
    }

    @Test
    fun activityDetached_keepsBroadcastControllable() {
        broadcast.startBroadcast(args, RecordingResult())
        advertiser.succeed()

        broadcast.onActivityDetached()
        val stop = RecordingResult()
        broadcast.stopBroadcast(stop)

        assertEquals(true, stop.successValue)
        assertFalse(advertiser.isStarted)
    }

    // Gap #4 (Android): concurrent and repeated starts

    @Test
    fun secondStartWhilePending_isRejectedAndFirstStillCompletes() {
        val first = RecordingResult()
        val second = RecordingResult()
        broadcast.startBroadcast(args, first)
        broadcast.startBroadcast(args, second)

        assertEquals("ALREADY_STARTING", second.errorMessage)
        advertiser.succeed()
        assertTrue(first.succeeded)
        assertEquals(1, advertiser.startCalls)
    }

    @Test
    fun startWhileAdvertising_restartsWithNewData() {
        broadcast.startBroadcast(args, RecordingResult())
        advertiser.succeed()

        val result = RecordingResult()
        broadcast.startBroadcast(args + ("major" to 7), result)

        assertEquals(1, advertiser.stopCalls)
        assertEquals("7", advertiser.lastBeacon!!.id2.toString())
        advertiser.succeed()
        assertTrue(result.succeeded)
    }

    // Android variant of gap #1: start must never hang

    @Test
    fun start_timesOutWhenRadioNeverAnswers() {
        val result = RecordingResult()
        broadcast.startBroadcast(args, result)

        timeoutAction!!.invoke()

        assertEquals("START_TIMEOUT", result.errorMessage)
        advertiser.succeed()
        assertFalse(advertiser.isStarted, "late success after timeout must be stopped")
        assertEquals(1, result.calls)
    }

    @Test
    fun lateSuccessOfOldAttempt_stopsOnlyThatAttempt() {
        broadcast.startBroadcast(args, RecordingResult())
        broadcast.stopBroadcast(RecordingResult())
        val second = RecordingResult()
        broadcast.startBroadcast(args, second)

        advertiser.succeed(index = 0)
        advertiser.succeed(index = 1)

        assertTrue(second.succeeded)
        assertEquals(1, advertiser.liveCount, "only the second attempt may stay on air")
        assertTrue(advertiser.created[1].isStarted)
    }

    // Verifier advisories A1/A2 and uncovered paths

    @Test
    fun malformedUuid_isRejectedWithoutWedgingLaterCalls() {
        val bad = RecordingResult()
        broadcast.startBroadcast(args + ("proximityUUID" to "not-a-uuid"), bad)

        assertEquals("Invalid parameter", bad.errorMessage)
        assertEquals(0, advertiser.startCalls)

        val next = RecordingResult()
        broadcast.startBroadcast(args, next)
        advertiser.succeed()
        assertTrue(next.succeeded)

        val stop = RecordingResult()
        broadcast.stopBroadcast(stop)
        assertEquals(true, stop.successValue)
        assertFalse(advertiser.isStarted)
    }

    @Test
    fun lateSuccessOfStoppedAttempt_isNotCreditedToStartAwaitingPermission() {
        broadcast.startBroadcast(args, RecordingResult())
        broadcast.stopBroadcast(RecordingResult())

        needsPermission = true
        val waiting = RecordingResult()
        broadcast.startBroadcast(args + ("major" to 9), waiting)

        advertiser.succeed(index = 0)

        assertEquals(0, waiting.calls, "must keep waiting for the permission result")
        assertFalse(advertiser.isStarted, "stopped attempt must not stay on air")

        broadcast.onAdvertisePermissionResult(granted = true)
        advertiser.succeed()
        assertTrue(waiting.succeeded)
        assertEquals("9", advertiser.lastBeacon!!.id2.toString())
    }

    @Test
    fun permissionResultAfterActivityDetach_isIgnored() {
        needsPermission = true
        val result = RecordingResult()
        broadcast.startBroadcast(args, result)
        broadcast.onActivityDetached()

        broadcast.onAdvertisePermissionResult(granted = true)

        assertEquals(1, result.calls)
        assertEquals(0, advertiser.startCalls)
    }

    @Test
    fun disposeWhileAwaitingPermission_failsOnceAndIgnoresLaterGrant() {
        needsPermission = true
        val result = RecordingResult()
        broadcast.startBroadcast(args, result)

        broadcast.dispose()
        broadcast.onAdvertisePermissionResult(granted = true)

        assertEquals(1, result.calls)
        assertEquals(0, advertiser.startCalls)
    }

    @Test
    fun failedRestart_leavesNothingOnAir() {
        broadcast.startBroadcast(args, RecordingResult())
        advertiser.succeed()

        val restart = RecordingResult()
        broadcast.startBroadcast(args, restart)
        advertiser.fail(3)

        assertEquals("ALREADY_STARTED", restart.errorMessage)
        assertEquals(0, advertiser.liveCount)
    }

    // Gap #8: unsupported hardware fails fast

    @Test
    fun start_failsWhenTransmissionUnsupported() {
        supported = false
        val result = RecordingResult()
        broadcast.startBroadcast(args, result)

        assertEquals("BROADCAST_NOT_SUPPORTED", result.errorMessage)
        assertEquals(0, advertiser.startCalls)
        assertEquals(0, permissionRequests)

        supported = true
        val next = RecordingResult()
        broadcast.startBroadcast(args, next)
        advertiser.succeed()
        assertTrue(next.succeeded)
    }

    // Gap #9: broadcasting state stream

    @Test
    fun state_emitsTrueOnStartAndFalseOnStop() {
        broadcast.startBroadcast(args, RecordingResult())
        assertEquals(emptyList(), states, "nothing on air until the radio confirms")
        advertiser.succeed()
        broadcast.stopBroadcast(RecordingResult())

        assertEquals(listOf(true, false), states)
        assertFalse(broadcast.isAdvertising)
    }

    @Test
    fun state_reportsRestartTransitions() {
        broadcast.startBroadcast(args, RecordingResult())
        advertiser.succeed()
        broadcast.startBroadcast(args + ("major" to 5), RecordingResult())
        advertiser.succeed()

        assertEquals(listOf(true, false, true), states)
    }

    @Test
    fun bluetoothOff_whileAdvertising_stopsAndEmitsFalse() {
        broadcast.startBroadcast(args, RecordingResult())
        advertiser.succeed()

        bluetoothOff!!.invoke()

        assertEquals(listOf(true, false), states)
        assertFalse(advertiser.isStarted)
        val isBroadcasting = RecordingResult()
        broadcast.isBroadcasting(isBroadcasting)
        assertEquals(false, isBroadcasting.successValue)
    }

    @Test
    fun bluetoothOff_whileStarting_failsPendingStart() {
        val result = RecordingResult()
        broadcast.startBroadcast(args, result)

        bluetoothOff!!.invoke()

        assertEquals("BLUETOOTH_OFF", result.errorMessage)
        advertiser.succeed()
        assertFalse(advertiser.isStarted, "late success after Bluetooth off must be stopped")
    }

    @Test
    fun dispose_stopsWatchingBluetooth() {
        broadcast.dispose()

        assertTrue(bluetoothWatchStopped)
    }

    @Test
    fun stopWhilePending_failsPendingStart() {
        val start = RecordingResult()
        broadcast.startBroadcast(args, start)
        broadcast.stopBroadcast(RecordingResult())

        assertEquals(1, start.calls)
        assertTrue(start.errorMessage!!.contains("stopped"))
    }
}
