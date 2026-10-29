import 'package:dchs_flutter_beacon/dchs_flutter_beacon.dart';
import 'package:dchs_flutter_beacon/dchs_flutter_beacon_platform_interface.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

class _LegacyPlatform extends DchsFlutterBeaconPlatform
    with MockPlatformInterfaceMixin {
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = EventChannel('flutter_broadcast_state_changed');

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockStreamHandler(channel, null);
  });

  test('broadcastingStateChanged emits native broadcasting states', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockStreamHandler(
      channel,
      MockStreamHandler.inline(onListen: (arguments, events) {
        events.success(true);
        events.success(false);
      }),
    );

    expect(await flutterBeacon.broadcastingStateChanged().take(2).toList(),
        [true, false]);
  });

  test('broadcastingStateChanged maps non-true events to false', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockStreamHandler(
      channel,
      MockStreamHandler.inline(onListen: (arguments, events) {
        events.success(null);
        events.success(1);
        events.success(true);
      }),
    );

    expect(await flutterBeacon.broadcastingStateChanged().take(3).toList(),
        [false, false, true]);
  });

  test('broadcastingStateChanged returns the same cached stream', () {
    expect(
      identical(flutterBeacon.broadcastingStateChanged(),
          flutterBeacon.broadcastingStateChanged()),
      isTrue,
    );
  });

  test('platform implementations that predate the stream still compile and '
      'report it as unimplemented', () {
    expect(() => _LegacyPlatform().broadcastingStateChanged(),
        throwsUnimplementedError);
  });
}
