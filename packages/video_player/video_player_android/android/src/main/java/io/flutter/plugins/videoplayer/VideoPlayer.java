// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer;

import static androidx.media3.common.Player.REPEAT_MODE_ALL;
import static androidx.media3.common.Player.REPEAT_MODE_OFF;

import android.content.Context;
import android.view.Surface;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Player.Listener;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.MappingTrackSelector;
import io.flutter.plugin.common.EventChannel;
import io.flutter.view.TextureRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@UnstableApi
final class VideoPlayer {
  private ExoPlayer exoPlayer;
  private Surface surface;
  private final TextureRegistry.SurfaceTextureEntry textureEntry;
  private final VideoPlayerCallbacks videoPlayerEvents;
  private final VideoPlayerOptions options;

  /**
   * Creates a video player.
   *
   * @param context application context.
   * @param events event callbacks.
   * @param textureEntry texture to render to.
   * @param asset asset to play.
   * @param options options for playback.
   * @return a video player instance.
   */
  @NonNull
  static VideoPlayer create(
      Context context,
      VideoPlayerCallbacks events,
      TextureRegistry.SurfaceTextureEntry textureEntry,
      VideoAsset asset,
      String audioTrackName,
      VideoPlayerOptions options) {
    DefaultTrackSelector trackSelector = new DefaultTrackSelector(context);
    if (audioTrackName != null) {
      trackSelector.setParameters(
          trackSelector
              .buildUponParameters()
              .setPreferredAudioLanguage(audioTrackName));
    }

    ExoPlayer.Builder builder =
        new ExoPlayer.Builder(context)
            .setMediaSourceFactory(asset.getMediaSourceFactory(context))
            .setTrackSelector(trackSelector);
    return new VideoPlayer(builder, events, textureEntry, asset.getMediaItem(), options);
  }

  @VisibleForTesting
  VideoPlayer(
      ExoPlayer.Builder builder,
      VideoPlayerCallbacks events,
      TextureRegistry.SurfaceTextureEntry textureEntry,
      MediaItem mediaItem,
      VideoPlayerOptions options) {
    this.videoPlayerEvents = events;
    this.textureEntry = textureEntry;
    this.options = options;

    ExoPlayer exoPlayer = builder.build();
    exoPlayer.setMediaItem(mediaItem);
    exoPlayer.prepare();

    setUpVideoPlayer(exoPlayer);
  }

  private void setUpVideoPlayer(ExoPlayer exoPlayer) {
    this.exoPlayer = exoPlayer;

    surface = new Surface(textureEntry.surfaceTexture());
    exoPlayer.setVideoSurface(surface);
    setAudioAttributes(exoPlayer, options.mixWithOthers);
    exoPlayer.addListener(new ExoPlayerEventListener(exoPlayer, videoPlayerEvents));
  }

  void sendBufferingUpdate() {
    videoPlayerEvents.onBufferingUpdate(exoPlayer.getBufferedPosition());
  }

  private static void setAudioAttributes(ExoPlayer exoPlayer, boolean isMixMode) {
    exoPlayer.setAudioAttributes(
        new AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
        !isMixMode);
  }

  void play() {
    exoPlayer.setPlayWhenReady(true);
  }

  void pause() {
    exoPlayer.setPlayWhenReady(false);
  }

  void setLooping(boolean value) {
    exoPlayer.setRepeatMode(value ? REPEAT_MODE_ALL : REPEAT_MODE_OFF);
  }

  void setVolume(double value) {
    float bracketedValue = (float) Math.max(0.0, Math.min(1.0, value));
    exoPlayer.setVolume(bracketedValue);
  }

  @OptIn(markerClass = UnstableApi.class)
  ArrayList<String> getAvailableAudioTracksList() {
    MappingTrackSelector.MappedTrackInfo mappedTrackInfo = trackSelector.getCurrentMappedTrackInfo();
    ArrayList<String> audioTrackNames = new ArrayList<String>();
    if (mappedTrackInfo == null) {
      return audioTrackNames;
    }

    for (int rendererIndex = 0; rendererIndex < mappedTrackInfo.getRendererCount(); rendererIndex++) {
      if (mappedTrackInfo.getRendererType(rendererIndex) != C.TRACK_TYPE_AUDIO)
        continue;
      TrackGroupArray trackGroupArray = mappedTrackInfo.getTrackGroups(rendererIndex);
      for (int trackGroupIndex = 0; trackGroupIndex < trackGroupArray.length; trackGroupIndex++) {
        TrackGroup group = trackGroupArray.get(trackGroupIndex);
        for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
          if ((mappedTrackInfo.getTrackSupport(rendererIndex, trackGroupIndex, trackIndex))
              == C.FORMAT_HANDLED) {
            String name = getAudioTrackName(group.getFormat(trackIndex));
            audioTrackNames.add(name);
          }
        }
      }
    }

    // On iOS, if the amount of audio tracks is 1, and the only audio track has no language tag (undefined),
    // it returns an empty array of audio tracks for some reason (yet still plays the audio).
    // To match this behavior on Android, we use this hack.
    if (audioTrackNames.size() == 1) {
      if (audioTrackNames.get(0).equals("und")) {
        return new ArrayList<String>();
      }
    }
    return audioTrackNames;
  }

  @OptIn(markerClass = UnstableApi.class)
  void setActiveAudioTrack(String audioTrackName) {
    MappingTrackSelector.MappedTrackInfo mappedTrackInfo = trackSelector.getCurrentMappedTrackInfo();
    if (mappedTrackInfo != null) {
      for (int rendererIndex = 0; rendererIndex < mappedTrackInfo.getRendererCount(); rendererIndex++) {
        if (mappedTrackInfo.getRendererType(rendererIndex) != C.TRACK_TYPE_AUDIO)
          continue;
        TrackGroupArray trackGroupArray = mappedTrackInfo.getTrackGroups(rendererIndex);
        for (int trackGroupIndex = 0; trackGroupIndex < trackGroupArray.length; trackGroupIndex++) {
          TrackGroup group = trackGroupArray.get(trackGroupIndex);
          for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
            if (getAudioTrackName(group.getFormat(trackIndex)).equals(audioTrackName)) {
              applyAudioTrackSettings(rendererIndex, trackIndex, trackGroupIndex, mappedTrackInfo);
              return;
            }
          }
        }
      }
    }
  }

  @OptIn(markerClass = UnstableApi.class)
  void setActiveAudioTrackByIndex(int index) {
    MappingTrackSelector.MappedTrackInfo mappedTrackInfo = trackSelector.getCurrentMappedTrackInfo();
    int audioIndex = 0;
    if (mappedTrackInfo != null) {
      for (int rendererIndex = 0; rendererIndex < mappedTrackInfo.getRendererCount(); rendererIndex++) {
        if (mappedTrackInfo.getRendererType(rendererIndex) != C.TRACK_TYPE_AUDIO)
          continue;
        TrackGroupArray trackGroupArray = mappedTrackInfo.getTrackGroups(rendererIndex);
        for (int trackGroupIndex = 0; trackGroupIndex < trackGroupArray.length; trackGroupIndex++) {
          TrackGroup group = trackGroupArray.get(trackGroupIndex);
          for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
            if (audioIndex == index) {
              applyAudioTrackSettings(rendererIndex, trackIndex, trackGroupIndex, mappedTrackInfo);
              return;
            }
            audioIndex++;
          }
        }
      }
    }
  }

  @OptIn(markerClass = UnstableApi.class)
  private String getAudioTrackName(Format groupFormat) {
    String name = groupFormat.language;
    if (name == null) {
      name = "und"; // as defined in ISO 639-2
    }
    return name;
  }

  @OptIn(markerClass = UnstableApi.class)
  private void applyAudioTrackSettings(int rendererIndex, int trackIndex, int trackGroupIndex, MappingTrackSelector.MappedTrackInfo mappedTrackInfo) {
    DefaultTrackSelector.Parameters.Builder builder = trackSelector.buildUponParameters();
    //builder.clearOverridesOfType(C.TRACK_TYPE_AUDIO);
    builder.clearOverridesOfType(rendererIndex);
    builder.setRendererDisabled(rendererIndex, false);
    TrackGroup trackGroup = mappedTrackInfo.getTrackGroups(rendererIndex).get(trackGroupIndex);
    TrackSelectionOverride trackSelectionOverride = new TrackSelectionOverride(trackGroup, trackIndex);
    builder.addOverride(trackSelectionOverride);
    trackSelector.setParameters(builder.build());
  }

  void setPlaybackSpeed(double value) {
    // We do not need to consider pitch and skipSilence for now as we do not handle them and
    // therefore never diverge from the default values.
    final PlaybackParameters playbackParameters = new PlaybackParameters(((float) value));

    exoPlayer.setPlaybackParameters(playbackParameters);
  }

  void seekTo(int location) {
    exoPlayer.seekTo(location);
  }

  long getPosition() {
    return exoPlayer.getCurrentPosition();
  }

  void dispose() {
    textureEntry.release();
    if (surface != null) {
      surface.release();
    }
    if (exoPlayer != null) {
      exoPlayer.release();
    }
  }
}
