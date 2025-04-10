// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer;

import static androidx.media3.common.Player.REPEAT_MODE_ALL;
import static androidx.media3.common.Player.REPEAT_MODE_OFF;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.MappingTrackSelector;
import io.flutter.view.TextureRegistry;
import java.util.ArrayList;

@UnstableApi
final class VideoPlayer implements TextureRegistry.SurfaceProducer.Callback {
  @NonNull private final ExoPlayerProvider exoPlayerProvider;
  @NonNull private final MediaItem mediaItem;
  @NonNull private final TextureRegistry.SurfaceProducer surfaceProducer;
  @NonNull private final VideoPlayerCallbacks videoPlayerEvents;
  @NonNull private final VideoPlayerOptions options;
  @NonNull private ExoPlayer exoPlayer;
  @Nullable private ExoPlayerState savedStateDuring;

  /**
   * Creates a video player.
   *
   * @param context application context.
   * @param events event callbacks.
   * @param surfaceProducer produces a texture to render to.
   * @param asset asset to play.
   * @param options options for playback.
   * @return a video player instance.
   */
  @NonNull
  static VideoPlayer create(
      @NonNull Context context,
      @NonNull VideoPlayerCallbacks events,
      @NonNull TextureRegistry.SurfaceProducer surfaceProducer,
      @NonNull VideoAsset asset,
      @Nullable String audioTrackName,
      @NonNull VideoPlayerOptions options) {
    return new VideoPlayer(
        () -> {
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
          return builder.build();
        },
        events,
        surfaceProducer,
        asset.getMediaItem(),
        options);
  }

  /** A closure-compatible signature since {@link java.util.function.Supplier} is API level 24. */
  interface ExoPlayerProvider {
    /**
     * Returns a new {@link ExoPlayer}.
     *
     * @return new instance.
     */
    ExoPlayer get();
  }

  @VisibleForTesting
  VideoPlayer(
      @NonNull ExoPlayerProvider exoPlayerProvider,
      @NonNull VideoPlayerCallbacks events,
      @NonNull TextureRegistry.SurfaceProducer surfaceProducer,
      @NonNull MediaItem mediaItem,
      @NonNull VideoPlayerOptions options) {
    this.exoPlayerProvider = exoPlayerProvider;
    this.videoPlayerEvents = events;
    this.surfaceProducer = surfaceProducer;
    this.mediaItem = mediaItem;
    this.options = options;
    this.exoPlayer = createVideoPlayer();
    surfaceProducer.setCallback(this);
  }

  @RestrictTo(RestrictTo.Scope.LIBRARY)
  // TODO(matanlurey): https://github.com/flutter/flutter/issues/155131.
  @SuppressWarnings({"deprecation", "removal"})
  public void onSurfaceCreated() {
    if (savedStateDuring != null) {
      exoPlayer = createVideoPlayer();
      savedStateDuring.restore(exoPlayer);
      savedStateDuring = null;
    }
  }

  @RestrictTo(RestrictTo.Scope.LIBRARY)
  public void onSurfaceDestroyed() {
    exoPlayer.stop();
    savedStateDuring = ExoPlayerState.save(exoPlayer);
    exoPlayer.release();
  }

  private ExoPlayer createVideoPlayer() {
    ExoPlayer exoPlayer = exoPlayerProvider.get();
    exoPlayer.setMediaItem(mediaItem);
    exoPlayer.prepare();

    exoPlayer.setVideoSurface(surfaceProducer.getSurface());

    boolean wasInitialized = savedStateDuring != null;
    exoPlayer.addListener(new ExoPlayerEventListener(exoPlayer, videoPlayerEvents, wasInitialized));
    setAudioAttributes(exoPlayer, options.mixWithOthers);

    return exoPlayer;
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
    exoPlayer.play();
  }

  void pause() {
    exoPlayer.pause();
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
    DefaultTrackSelector trackSelector = (DefaultTrackSelector) exoPlayer.getTrackSelector();
    MappingTrackSelector.MappedTrackInfo mappedTrackInfo = trackSelector.getCurrentMappedTrackInfo();
    ArrayList<String> audioTrackNames = new ArrayList<>();
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
        return new ArrayList<>();
      }
    }
    return audioTrackNames;
  }

  @OptIn(markerClass = UnstableApi.class)
  void setActiveAudioTrack(String audioTrackName) {
    DefaultTrackSelector trackSelector = (DefaultTrackSelector) exoPlayer.getTrackSelector();
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
    DefaultTrackSelector trackSelector = (DefaultTrackSelector) exoPlayer.getTrackSelector();
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
  private void applyAudioTrackSettings(int rendererIndex, int trackIndex, int trackGroupIndex,
                                       MappingTrackSelector.MappedTrackInfo mappedTrackInfo) {
    DefaultTrackSelector trackSelector = (DefaultTrackSelector) exoPlayer.getTrackSelector();
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
    surfaceProducer.release();
    exoPlayer.release();
  }
}
