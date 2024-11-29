// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
// Autogenerated from Pigeon (v13.0.0), do not edit directly.
// See also: https://pub.dev/packages/pigeon

#import <Foundation/Foundation.h>

@protocol FlutterBinaryMessenger;
@protocol FlutterMessageCodec;
@class FlutterError;
@class FlutterStandardTypedData;

NS_ASSUME_NONNULL_BEGIN

/// Possible outcomes of an authentication attempt.
typedef NS_ENUM(NSUInteger, FLAAuthResult) {
  /// The user authenticated successfully.
  FLAAuthResultSuccess = 0,
  /// The user failed to successfully authenticate.
  FLAAuthResultFailure = 1,
  /// The authentication system was not available.
  FLAAuthResultErrorNotAvailable = 2,
  /// No biometrics are enrolled.
  FLAAuthResultErrorNotEnrolled = 3,
  /// No passcode is set.
  FLAAuthResultErrorPasscodeNotSet = 4,
};

/// Wrapper for FLAAuthResult to allow for nullability.
@interface FLAAuthResultBox : NSObject
@property(nonatomic, assign) FLAAuthResult value;
- (instancetype)initWithValue:(FLAAuthResult)value;
@end

/// Pigeon equivalent of the subset of BiometricType used by iOS.
typedef NS_ENUM(NSUInteger, FLAAuthBiometric) {
  FLAAuthBiometricFace = 0,
  FLAAuthBiometricFingerprint = 1,
};

/// Wrapper for FLAAuthBiometric to allow for nullability.
@interface FLAAuthBiometricBox : NSObject
@property(nonatomic, assign) FLAAuthBiometric value;
- (instancetype)initWithValue:(FLAAuthBiometric)value;
@end

@class FLAAuthStrings;
@class FLAAuthOptions;
@class FLAAuthResultDetails;
@class FLAAuthBiometricWrapper;

/// Pigeon version of IOSAuthMessages, plus the authorization reason.
///
/// See auth_messages_ios.dart for details.
@interface FLAAuthStrings : NSObject
/// `init` unavailable to enforce nonnull fields, see the `make` class method.
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)makeWithReason:(NSString *)reason
                       lockOut:(NSString *)lockOut
            goToSettingsButton:(NSString *)goToSettingsButton
       goToSettingsDescription:(NSString *)goToSettingsDescription
                  cancelButton:(NSString *)cancelButton
        localizedFallbackTitle:(nullable NSString *)localizedFallbackTitle;
@property(nonatomic, copy) NSString *reason;
@property(nonatomic, copy) NSString *lockOut;
@property(nonatomic, copy) NSString *goToSettingsButton;
@property(nonatomic, copy) NSString *goToSettingsDescription;
@property(nonatomic, copy) NSString *cancelButton;
@property(nonatomic, copy, nullable) NSString *localizedFallbackTitle;
@end

@interface FLAAuthOptions : NSObject
/// `init` unavailable to enforce nonnull fields, see the `make` class method.
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)makeWithBiometricOnly:(BOOL)biometricOnly
                               sticky:(BOOL)sticky
                      useErrorDialogs:(BOOL)useErrorDialogs;
@property(nonatomic, assign) BOOL biometricOnly;
@property(nonatomic, assign) BOOL sticky;
@property(nonatomic, assign) BOOL useErrorDialogs;
@end

@interface FLAAuthResultDetails : NSObject
/// `init` unavailable to enforce nonnull fields, see the `make` class method.
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)makeWithResult:(FLAAuthResult)result
                  errorMessage:(nullable NSString *)errorMessage
                  errorDetails:(nullable NSString *)errorDetails;
/// The result of authenticating.
@property(nonatomic, assign) FLAAuthResult result;
/// A system-provided error message, if any.
@property(nonatomic, copy, nullable) NSString *errorMessage;
/// System-provided error details, if any.
@property(nonatomic, copy, nullable) NSString *errorDetails;
@end

@interface FLAAuthBiometricWrapper : NSObject
/// `init` unavailable to enforce nonnull fields, see the `make` class method.
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)makeWithValue:(FLAAuthBiometric)value;
@property(nonatomic, assign) FLAAuthBiometric value;
@end

/// The codec used by FLALocalAuthApi.
NSObject<FlutterMessageCodec> *FLALocalAuthApiGetCodec(void);

@protocol FLALocalAuthApi
/// Returns true if this device supports authentication.
///
/// @return `nil` only when `error != nil`.
- (nullable NSNumber *)isDeviceSupportedWithError:(FlutterError *_Nullable *_Nonnull)error;
/// Returns true if this device can support biometric authentication, whether
/// any biometrics are enrolled or not.
///
/// @return `nil` only when `error != nil`.
- (nullable NSNumber *)deviceCanSupportBiometricsWithError:(FlutterError *_Nullable *_Nonnull)error;
/// Returns the biometric types that are enrolled, and can thus be used
/// without additional setup.
///
/// @return `nil` only when `error != nil`.
- (nullable NSArray<FLAAuthBiometricWrapper *> *)getEnrolledBiometricsWithError:
    (FlutterError *_Nullable *_Nonnull)error;
/// Attempts to authenticate the user with the provided [options], and using
/// [strings] for any UI.
- (void)authenticateWithOptions:(FLAAuthOptions *)options
                        strings:(FLAAuthStrings *)strings
                     completion:(void (^)(FLAAuthResultDetails *_Nullable,
                                          FlutterError *_Nullable))completion;
@end

extern void SetUpFLALocalAuthApi(id<FlutterBinaryMessenger> binaryMessenger,
                                 NSObject<FLALocalAuthApi> *_Nullable api);

NS_ASSUME_NONNULL_END
