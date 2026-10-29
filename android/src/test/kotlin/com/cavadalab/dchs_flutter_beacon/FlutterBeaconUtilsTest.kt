package com.cavadalab.dchs_flutter_beacon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.altbeacon.beacon.MonitorNotifier

internal class FlutterBeaconUtilsTest {
    @Test
    fun parseState_returnsExpectedLabels() {
        assertEquals("INSIDE", FlutterBeaconUtils.parseState(MonitorNotifier.INSIDE))
        assertEquals("OUTSIDE", FlutterBeaconUtils.parseState(MonitorNotifier.OUTSIDE))
        assertEquals("UNKNOWN", FlutterBeaconUtils.parseState(Int.MIN_VALUE))
    }

    @Test
    fun regionFromMap_buildsRegionWithIdentifiers() {
        val region = FlutterBeaconUtils.regionFromMap(
            mapOf(
                "identifier" to "region-1",
                "proximityUUID" to "74278BDA-B644-4520-8F0C-720EAF059935",
                "major" to 10,
                "minor" to 20
            )
        )

        assertNotNull(region)
        assertEquals("region-1", region.uniqueId)
        assertEquals("74278bda-b644-4520-8f0c-720eaf059935", region.id1.toString())
        assertEquals(10, region.id2?.toInt())
        assertEquals(20, region.id3?.toInt())
    }

    @Test
    fun regionFromMap_returnsNullForInvalidUuid() {
        val region = FlutterBeaconUtils.regionFromMap(
            mapOf(
                "identifier" to "broken-region",
                "proximityUUID" to "not-a-uuid"
            )
        )

        assertNull(region)
    }

    private val validBeacon = mapOf<String, Any?>(
        "proximityUUID" to "CB10023F-A318-3394-4199-A8730C7C1AEC",
        "major" to 65535,
        "minor" to 0,
        "txPower" to -128,
    )

    @Test
    fun beaconFromMap_buildsIBeaconFromValidData() {
        val beacon = FlutterBeaconUtils.beaconFromMap(validBeacon)

        assertEquals("cb10023f-a318-3394-4199-a8730c7c1aec", beacon.id1.toString())
        assertEquals("65535", beacon.id2.toString())
        assertEquals("0", beacon.id3.toString())
        assertEquals(-128, beacon.txPower)
        assertEquals(0x004C, beacon.manufacturer)
    }

    @Test
    fun beaconFromMap_defaultsTxPowerWhenMissing() {
        val beacon = FlutterBeaconUtils.beaconFromMap(validBeacon - "txPower")

        assertEquals(-59, beacon.txPower)
    }

    @Test
    fun beaconFromMap_rejectsInvalidValues() {
        val invalid = listOf(
            validBeacon - "proximityUUID",
            validBeacon + ("proximityUUID" to "123"),
            validBeacon + ("proximityUUID" to "CB10023FA31833944199A8730C7C1AEC"),
            validBeacon - "major",
            validBeacon + ("major" to "1"),
            validBeacon + ("major" to -1),
            validBeacon + ("minor" to 65536),
            validBeacon + ("txPower" to 128),
            validBeacon + ("txPower" to "x"),
        )
        for (map in invalid) {
            assertFailsWith<IllegalArgumentException>("expected rejection of $map") {
                FlutterBeaconUtils.beaconFromMap(map)
            }
        }
    }
}
