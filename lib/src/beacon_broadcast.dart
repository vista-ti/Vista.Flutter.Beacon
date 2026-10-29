//  Copyright (c) 2018 Eyro Labs.
//  Licensed under Apache License v2.0 that can be
//  found in the LICENSE file.

import 'package:flutter/foundation.dart';

/// Class for managing Beacon Broadcast object.
class BeaconBroadcast {
  /// The unique identifier of region.
  final String? identifier;

  /// The proximity UUID of beacon.
  final String proximityUUID;

  /// The major value of beacon.
  final int major;

  /// The minor value of beacon.
  final int minor;

  /// The txPower value of beacon. Specify null to use the default value for the device.
  final int? txPower;

  /// Android advertising frequency. Defaults to [AdvertisingMode.high]
  /// (shortest interval) so scanners detect the beacon as fast as possible.
  final AdvertisingMode? advertisingMode;

  final AdvertisingTxPowerLevel? advertisingTxPowerLevel;

  BeaconBroadcast({
    this.identifier = 'com.flutterBeacon',
    required this.proximityUUID,
    required this.major,
    required this.minor,
    this.txPower,
    this.advertisingMode = AdvertisingMode.high,
    this.advertisingTxPowerLevel = AdvertisingTxPowerLevel.high,
  }) {
    if (!_uuidPattern.hasMatch(proximityUUID)) {
      throw ArgumentError.value(
          proximityUUID, 'proximityUUID', 'Must be a UUID (8-4-4-4-12 hex)');
    }
    _checkRange(major, 0, 65535, 'major');
    _checkRange(minor, 0, 65535, 'minor');
    if (txPower != null) {
      _checkRange(txPower!, -128, 127, 'txPower');
    }
    if (defaultTargetPlatform == TargetPlatform.iOS &&
        (identifier == null || identifier!.isEmpty)) {
      throw ArgumentError.value(
          identifier, 'identifier', 'Must not be null or empty on iOS');
    }
  }

  static final RegExp _uuidPattern = RegExp(
      r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$');

  static void _checkRange(int value, int min, int max, String name) {
    if (value < min || value > max) {
      throw RangeError.range(value, min, max, name);
    }
  }

  /// Serialize current instance object into [Map].
  dynamic get toJson {
    final map = <String, dynamic>{
      'proximityUUID': proximityUUID,
      'major': major,
      'minor': minor,
      'txPower': txPower,
    };

    if (advertisingMode != null) {
      map['advertisingMode'] = advertisingMode!.index;
    }

    if (advertisingTxPowerLevel != null) {
      map['advertisingTxPowerLevel'] = advertisingTxPowerLevel!.index;
    }

    if (identifier != null) {
      map['identifier'] = identifier!;
    }

    return map;
  }
}

enum AdvertisingMode { low, mid, high }

enum AdvertisingTxPowerLevel { ultraLow, low, mid, high }
