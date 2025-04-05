// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:video_player_platform_interface/video_player_platform_interface.dart';

import 'messages.g.dart';

/// An iOS implementation of [VideoPlayerPlatform] that uses the
/// Pigeon-generated [VideoPlayerApi].
class AVFoundationVideoPlayer extends VideoPlayerPlatform {
  final AVFoundationVideoPlayerApi _api = AVFoundationVideoPlayerApi();

  /// Registers this class as the default instance of [VideoPlayerPlatform].
  static void registerWith() {
    VideoPlayerPlatform.instance = AVFoundationVideoPlayer();
  }

  @override
  Future<void> init() {
    return _api.initialize();
  }

  @override
  Future<void> dispose(int textureId) {
    return _api.dispose(textureId);
  }

  @override
  Future<int?> create(DataSource dataSource) async {
    final CreationOptions options = _obtainCreateMessageFromDataSource(dataSource);
    return _api.create(options);
  }

  CreationOptions _obtainCreateMessageFromDataSource(DataSource dataSource) {
    String? asset;
    String? packageName;
    String? uri;
    String? formatHint;
    String? name;
    String? audioTrackName;
    Map<String, String> httpHeaders = <String, String>{};
    switch (dataSource.sourceType) {
      case DataSourceType.asset:
        asset = dataSource.asset;
        packageName = dataSource.package;
      case DataSourceType.network:
        uri = dataSource.uri;
        formatHint = _videoFormatStringMap[dataSource.formatHint];
        httpHeaders = dataSource.httpHeaders;
        name = dataSource.name;
        audioTrackName = dataSource.audioTrackName;
      case DataSourceType.file:
        uri = dataSource.uri;
      case DataSourceType.contentUri:
        uri = dataSource.uri;
    }
    return CreationOptions(
      asset: asset,
      packageName: packageName,
      uri: uri,
      httpHeaders: httpHeaders,
      formatHint: formatHint,
      name: name,
      audioTrackName: audioTrackName,
    );
  }

  @override
  Future<int?> createWithHlsCachingSupport(DataSource dataSource) async {
    final CreationOptions options = _obtainCreateMessageFromDataSource(dataSource);
    return _api.createWithHlsCachingSupport(options);
  }

  @override
  Future<void> setLooping(int textureId, bool looping) {
    return _api.setLooping(looping, textureId);
  }

  @override
  Future<void> startHlsStreamCachingIfNeeded(String urlString, String streamName, String audioTrackName) {
    final HlsStreamMessage message = HlsStreamMessage(
      uri: urlString,
      name: streamName,
      audioTrackName: audioTrackName,
      httpHeaders: <String?, String?>{},
    );
    return _api.startHlsStreamCachingIfNeeded(message);
  }

  @override
  Future<bool> isHlsAvailableOffline(String urlString, String? audioTrackName) async {
    final HlsStreamMessage message = HlsStreamMessage(
      uri: urlString,
      audioTrackName: audioTrackName,
      httpHeaders: <String?, String?>{},
    );
    return _api
        .isHlsAvailableOffline(message)
        .then((int isAvailable) => isAvailable != 0);
  }

  @override
  Future<void> play(int textureId) {
    return _api.play(textureId);
  }

  @override
  Future<void> pause(int textureId) {
    return _api.pause(textureId);
  }

  @override
  Future<void> setVolume(int textureId, double volume) {
    return _api.setVolume(volume, textureId);
  }

  @override
  Future<List<String?>> getAvailableAudioTracksList(int textureId) async {
    final AudioTrackMessage audioTrackMessage = await _api.getAvailableAudioTracksList(textureId);
    return audioTrackMessage.audioTrackNames!;
  }

  @override
  Future<void> setActiveAudioTrack(int textureId, String audioTrackName) {
    return _api.setActiveAudioTrack(
      AudioTrackMessage(textureId: textureId)..audioTrackNames = <String>[audioTrackName],
    );
  }

  @override
  Future<void> setActiveAudioTrackByIndex(int textureId, int index) {
    return _api.setActiveAudioTrackByIndex(
      AudioTrackMessage(textureId: textureId)..index = index,
    );
  }

  @override
  Future<void> setPlaybackSpeed(int textureId, double speed) {
    assert(speed > 0);

    return _api.setPlaybackSpeed(speed, textureId);
  }

  @override
  Future<void> seekTo(int textureId, Duration position) {
    return _api.seekTo(position.inMilliseconds, textureId);
  }

  @override
  Future<Duration> getPosition(int textureId) async {
    final int position = await _api.getPosition(textureId);
    return Duration(milliseconds: position);
  }

  @override
  Stream<VideoEvent> videoEventsFor(int textureId) {
    return _eventChannelFor(textureId)
        .receiveBroadcastStream()
        .map((dynamic event) {
      final Map<dynamic, dynamic> map = event as Map<dynamic, dynamic>;
      switch (map['event']) {
        case 'initialized':
          return VideoEvent(
            eventType: VideoEventType.initialized,
            duration: Duration(milliseconds: map['duration'] as int),
            size: Size((map['width'] as num?)?.toDouble() ?? 0.0,
                (map['height'] as num?)?.toDouble() ?? 0.0),
          );
        case 'completed':
          return VideoEvent(
            eventType: VideoEventType.completed,
          );
        case 'bufferingUpdate':
          final List<dynamic> values = map['values'] as List<dynamic>;

          return VideoEvent(
            buffered: values.map<DurationRange>(_toDurationRange).toList(),
            eventType: VideoEventType.bufferingUpdate,
          );
        case 'bufferingStart':
          return VideoEvent(eventType: VideoEventType.bufferingStart);
        case 'bufferingEnd':
          return VideoEvent(eventType: VideoEventType.bufferingEnd);
        case 'isPlayingStateUpdate':
          return VideoEvent(
            eventType: VideoEventType.isPlayingStateUpdate,
            isPlaying: map['isPlaying'] as bool,
          );
        default:
          return VideoEvent(eventType: VideoEventType.unknown);
      }
    });
  }

  @override
  Widget buildView(int textureId) {
    return Texture(textureId: textureId);
  }

  @override
  Future<void> setMixWithOthers(bool mixWithOthers) {
    return _api.setMixWithOthers(mixWithOthers);
  }

  EventChannel _eventChannelFor(int textureId) {
    return EventChannel('flutter.io/videoPlayer/videoEvents$textureId');
  }

  static const Map<VideoFormat, String> _videoFormatStringMap =
      <VideoFormat, String>{
    VideoFormat.ss: 'ss',
    VideoFormat.hls: 'hls',
    VideoFormat.dash: 'dash',
    VideoFormat.other: 'other',
  };

  DurationRange _toDurationRange(dynamic value) {
    final List<dynamic> pair = value as List<dynamic>;
    return DurationRange(
      Duration(milliseconds: pair[0] as int),
      Duration(milliseconds: pair[1] as int),
    );
  }
}
