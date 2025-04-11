// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer;

import static androidx.media3.common.Player.REPEAT_MODE_ALL;
import static androidx.media3.common.Player.REPEAT_MODE_OFF;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

/**
 * A class responsible for managing video playback using {@link ExoPlayer}.
 *
 * <p>It provides methods to control playback, adjust volume, and handle seeking.
 */
public abstract class VideoPlayer {
  @NonNull private final ExoPlayerProvider exoPlayerProvider;
  @NonNull private final MediaItem mediaItem;
  @NonNull private final VideoPlayerOptions options;
  @NonNull protected final VideoPlayerCallbacks videoPlayerEvents;
  @NonNull protected ExoPlayer exoPlayer;

  /** A closure-compatible signature since {@link java.util.function.Supplier} is API level 24. */
  public interface ExoPlayerProvider {
    /**
     * Returns a new {@link ExoPlayer}.
     *
     * @return new instance.
     */
    @NonNull
    ExoPlayer get();
  }

  public VideoPlayer(
      @NonNull VideoPlayerCallbacks events,
      @NonNull MediaItem mediaItem,
      @NonNull VideoPlayerOptions options,
      @NonNull ExoPlayerProvider exoPlayerProvider) {
    this.videoPlayerEvents = events;
    this.mediaItem = mediaItem;
    this.options = options;
    this.exoPlayerProvider = exoPlayerProvider;
    this.exoPlayer = createVideoPlayer();
  }

  @NonNull
  protected ExoPlayer createVideoPlayer() {
    ExoPlayer exoPlayer = exoPlayerProvider.get();
    exoPlayer.setMediaItem(mediaItem);
    exoPlayer.prepare();

    exoPlayer.addListener(createExoPlayerEventListener(exoPlayer));
    setAudioAttributes(exoPlayer, options.mixWithOthers);

    return exoPlayer;
  }

  @NonNull
  protected abstract ExoPlayerEventListener createExoPlayerEventListener(
      @NonNull ExoPlayer exoPlayer);

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

  @NonNull
  public ExoPlayer getExoPlayer() {
    return exoPlayer;
  }

  public void dispose() {
    exoPlayer.release();
  }
}
