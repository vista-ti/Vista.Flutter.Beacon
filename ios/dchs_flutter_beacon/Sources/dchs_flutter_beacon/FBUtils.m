//
//  FBUtils.m
//  flutter_beacon
//
//  Created by Alann Maulana on 26/12/18.
//

#import "FBUtils.h"
#import <CoreLocation/CoreLocation.h>

@implementation FBUtils

+ (NSDictionary * _Nonnull) dictionaryFromCLBeacon:(CLBeacon*) beacon {
    NSString *proximity;
    switch (beacon.proximity) {
        case CLProximityUnknown:
            proximity = @"unknown";
            break;
        case CLProximityImmediate:
            proximity = @"immediate";
            break;
        case CLProximityNear:
            proximity = @"near";
            break;
        case CLProximityFar:
            proximity = @"far";
            break;
    }
    
    NSNumber *rssi = [NSNumber numberWithInteger:beacon.rssi];
    return @{
             @"proximityUUID": [beacon.proximityUUID UUIDString],
             @"major": beacon.major,
             @"minor": beacon.minor,
             @"rssi": rssi,
             @"accuracy": [NSString stringWithFormat:@"%.2f", beacon.accuracy],
             @"proximity": proximity
             };
}

+ (NSDictionary * _Nonnull) dictionaryFromCLBeaconRegion:(CLBeaconRegion*) region {
    id major = region.major;
    if (!major) {
        major = [NSNull null];
    }
    id minor = region.minor;
    if (!minor) {
        minor = [NSNull null];
    }
    
    return @{
             @"identifier": region.identifier,
             @"proximityUUID": [region.proximityUUID UUIDString],
             @"major": major,
             @"minor": minor,
             };
}

+ (CLBeaconRegion * _Nullable) regionFromDictionary:(NSDictionary*) dict {
    // Values come from Dart: reject wrong types, NSNull, malformed UUIDs and
    // out-of-range ids instead of passing nil/garbage to CLBeaconRegion (which crashes).
    id identifier = dict[@"identifier"];
    id proximityUUID = dict[@"proximityUUID"];
    id major = dict[@"major"];
    id minor = dict[@"minor"];

    if (![identifier isKindOfClass:[NSString class]] || ![proximityUUID isKindOfClass:[NSString class]]) {
        return nil;
    }
    NSUUID *uuid = [[NSUUID alloc] initWithUUIDString:proximityUUID];
    if (!uuid) {
        return nil;
    }
    BOOL hasMajor = [major isKindOfClass:[NSNumber class]];
    BOOL hasMinor = [minor isKindOfClass:[NSNumber class]];
    if ((hasMajor && ![self isBeaconIdValue:major]) || (hasMinor && ![self isBeaconIdValue:minor])) {
        return nil;
    }

    CLBeaconRegion *region = nil;
    if (hasMajor && hasMinor) {
        region = [[CLBeaconRegion alloc] initWithUUID:uuid major:[major unsignedShortValue] minor:[minor unsignedShortValue] identifier:identifier];
    } else if (hasMajor) {
        region = [[CLBeaconRegion alloc] initWithUUID:uuid major:[major unsignedShortValue] identifier:identifier];
    } else {
        region = [[CLBeaconRegion alloc] initWithUUID:uuid identifier:identifier];
    }

    return region;
}

+ (BOOL) isBeaconIdValue:(NSNumber*) value {
    long long number = [value longLongValue];
    return number >= 0 && number <= UINT16_MAX;
}

@end
