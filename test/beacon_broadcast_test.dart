import 'package:dchs_flutter_beacon/dchs_flutter_beacon.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';

const _validUuid = 'CB10023F-A318-3394-4199-A8730C7C1AEC';

void main() {
  tearDown(() => debugDefaultTargetPlatformOverride = null);

  group('BeaconBroadcast defaults', () {
    test('serializes required fields and defaults', () {
      final params = BeaconBroadcast(
        proximityUUID: _validUuid,
        major: 1,
        minor: 2,
      );

      expect(params.toJson, {
        'proximityUUID': _validUuid,
        'major': 1,
        'minor': 2,
        'txPower': null,
        'advertisingMode': AdvertisingMode.high.index,
        'advertisingTxPowerLevel': AdvertisingTxPowerLevel.high.index,
        'identifier': 'com.flutterBeacon',
      });
    });

    test('defaults to the fastest advertising mode', () {
      final params = BeaconBroadcast(
        proximityUUID: _validUuid,
        major: 1,
        minor: 2,
      );

      expect(params.advertisingMode, AdvertisingMode.high);
    });

    test('keeps an explicitly requested advertising mode', () {
      final params = BeaconBroadcast(
        proximityUUID: _validUuid,
        major: 1,
        minor: 2,
        advertisingMode: AdvertisingMode.low,
      );

      expect(params.toJson['advertisingMode'], AdvertisingMode.low.index);
    });

    test('accepts lowercase UUIDs and boundary values', () {
      final params = BeaconBroadcast(
        proximityUUID: _validUuid.toLowerCase(),
        major: 65535,
        minor: 0,
        txPower: -128,
      );

      expect(params.toJson['major'], 65535);
      expect(params.toJson['minor'], 0);
      expect(params.toJson['txPower'], -128);
    });
  });

  group('BeaconBroadcast validation', () {
    BeaconBroadcast build({
      String uuid = _validUuid,
      int major = 1,
      int minor = 2,
      int? txPower,
      String? identifier = 'com.flutterBeacon',
    }) =>
        BeaconBroadcast(
          proximityUUID: uuid,
          major: major,
          minor: minor,
          txPower: txPower,
          identifier: identifier,
        );

    for (final uuid in [
      '',
      'not-a-uuid',
      'CB10023F-A318-3394-4199-A8730C7C1AE',
      'CB10023FA31833944199A8730C7C1AEC',
      'ZB10023F-A318-3394-4199-A8730C7C1AEC',
    ]) {
      test('rejects invalid proximityUUID "$uuid"', () {
        expect(() => build(uuid: uuid), throwsArgumentError);
      });
    }

    for (final value in [-1, 65536]) {
      test('rejects major $value', () {
        expect(() => build(major: value), throwsArgumentError);
      });

      test('rejects minor $value', () {
        expect(() => build(minor: value), throwsArgumentError);
      });
    }

    for (final value in [-129, 128]) {
      test('rejects txPower $value', () {
        expect(() => build(txPower: value), throwsArgumentError);
      });
    }

    test('rejects a null identifier on iOS', () {
      debugDefaultTargetPlatformOverride = TargetPlatform.iOS;

      expect(() => build(identifier: null), throwsArgumentError);
    });

    test('rejects an empty identifier on iOS', () {
      debugDefaultTargetPlatformOverride = TargetPlatform.iOS;

      expect(() => build(identifier: ''), throwsArgumentError);
    });

    test('allows a null identifier on Android', () {
      debugDefaultTargetPlatformOverride = TargetPlatform.android;

      expect(build(identifier: null).toJson.containsKey('identifier'), isFalse);
    });
  });
}
