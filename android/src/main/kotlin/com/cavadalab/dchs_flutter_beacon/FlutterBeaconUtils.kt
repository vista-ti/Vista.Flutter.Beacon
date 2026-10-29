package com.cavadalab.dchs_flutter_beacon

import org.altbeacon.beacon.Beacon
import org.altbeacon.beacon.BeaconParser
import org.altbeacon.beacon.Identifier
import org.altbeacon.beacon.MonitorNotifier
import org.altbeacon.beacon.Region
import java.util.*

object FlutterBeaconUtils {

    fun parseState(state: Int): String {
        return when (state) {
            MonitorNotifier.INSIDE -> "INSIDE"
            MonitorNotifier.OUTSIDE -> "OUTSIDE"
            else -> "UNKNOWN"
        }
    }

    fun beaconsToArray(beacons: List<Beacon>?): List<Map<String, Any>> {
        val list = mutableListOf<Map<String, Any>>()
        beacons?.forEach { beacon ->
            val map = beaconToMap(beacon)
            list.add(map)
        }
        return list
    }

    private fun beaconToMap(beacon: Beacon): Map<String, Any> {
        val map = HashMap<String, Any>()
        map["proximityUUID"] = beacon.id1.toString().uppercase(Locale.getDefault())
        map["major"] = beacon.id2.toInt()
        map["minor"] = beacon.id3.toInt()
        map["rssi"] = beacon.rssi
        map["txPower"] = beacon.txPower
        map["accuracy"] = String.format(Locale.US, "%.2f", beacon.distance)
        map["macAddress"] = beacon.bluetoothAddress ?: ""
        return map
    }

    fun regionToMap(region: Region): Map<String, Any> {
        val map = HashMap<String, Any>()
        map["identifier"] = region.uniqueId
        region.id1?.let {
            map["proximityUUID"] = it.toString()
        }
        region.id2?.let {
            map["major"] = it.toInt()
        }
        region.id3?.let {
            map["minor"] = it.toInt()
        }
        return map
    }

    @Suppress("UNCHECKED_CAST")
    fun regionFromMap(map: Map<*, *>): Region? {
        return try {
            var identifier = ""
            val identifiers = mutableListOf<Identifier>()

            val objectIdentifier = map["identifier"]
            if (objectIdentifier is String) {
                identifier = objectIdentifier
            }

            val proximityUUID = map["proximityUUID"]
            if (proximityUUID is String) {
                identifiers.add(Identifier.parse(proximityUUID))
            }

            val major = map["major"]
            if (major is Int) {
                identifiers.add(Identifier.fromInt(major))
            }
            val minor = map["minor"]
            if (minor is Int) {
                identifiers.add(Identifier.fromInt(minor))
            }

            Region(identifier, identifiers)
        } catch (e: RuntimeException) {
            null
        }
    }

    private val UUID_PATTERN =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /**
     * Builds the iBeacon to advertise. Throws [IllegalArgumentException] for missing or
     * out-of-range values instead of silently advertising a malformed beacon.
     */
    fun beaconFromMap(map: Map<*, *>): Beacon {
        val proximityUUID = map["proximityUUID"]
        require(proximityUUID is String && UUID_PATTERN.matches(proximityUUID)) {
            "proximityUUID must be a UUID (8-4-4-4-12 hex)"
        }
        val major = map["major"]
        require(major is Int && major in 0..65535) { "major must be an integer in 0..65535" }
        val minor = map["minor"]
        require(minor is Int && minor in 0..65535) { "minor must be an integer in 0..65535" }
        val txPower = map["txPower"]
        require(txPower == null || (txPower is Int && txPower in -128..127)) {
            "txPower must be an integer in -128..127"
        }

        return Beacon.Builder()
            .setId1(proximityUUID)
            .setId2(major.toString())
            .setId3(minor.toString())
            .setTxPower((txPower as Int?) ?: -59)
            .setDataFields(listOf(0L))
            .setManufacturer(0x004C) // Apple Inc.
            .build()
    }
}
