package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

/** 每次真实播放会话只发送一次匿名计数，不重试有副作用的 POST。 */
public final class GogoPlaybackReporter {
  private static AudioPlayer active;
  private static Music song;
  private static Counter counter;

  private GogoPlaybackReporter() {}

  public static synchronized void start(Music music, AudioPlayer player) {
    active = music != null && music.isBvLibrary() ? player : null;
    song = active == null ? null : music;
    counter = active == null ? null : new Counter(System.nanoTime(), player.getCurrentTimeMillis());
  }

  public static synchronized void finish(AudioPlayer player) {
    if (active != player) return;
    active = null;
    song = null;
    counter = null;
  }

  public static synchronized void pause(AudioPlayer player) {
    if (active == player && counter != null)
      counter.reset(System.nanoTime(), player.getCurrentTimeMillis());
  }

  public static synchronized void observe(AudioPlayer player) {
    if (player != active || counter == null) return;
    if (!counter.observe(System.nanoTime(), player.getCurrentTimeMillis(), !player.isPausing()))
      return;
    final String id = song.getSourceId();
    final MusicPlatform platform = song.getSource();
    MultiThreadingUtil.runAsync(
        () -> {
          try {
            GogoApiClient.forPlatform(platform).reportPlay(id);
          } catch (Exception failure) {
            System.err.println(
                "[" + platform.getDisplayName() + "] 播放计数上报失败：" + failure.getMessage());
          }
        });
  }

  static final class Counter {
    private long lastNanos, played;
    private double lastPosition;
    private boolean reported;

    Counter(long now, double position) {
      reset(now, position);
    }

    void reset(long now, double position) {
      lastNanos = now;
      lastPosition = position;
      played = 0;
    }

    boolean observe(long now, double position, boolean playing) {
      long elapsed = Math.max(0, now - lastNanos);
      double delta = position - lastPosition;
      lastNanos = now;
      lastPosition = position;
      if (!playing) {
        played = 0;
        return false;
      }
      // 跳转不贡献时间；相同位置代表缓冲/暂停，采样间隔过长不凭空补账。
      if (delta > 0
          && delta <= Math.max(2000, elapsed / 1_000_000.0 * 4)
          && elapsed <= 1_000_000_000L) played += elapsed;
      if (!reported && played > 30_000_000_000L) {
        reported = true;
        return true;
      }
      return false;
    }
  }
}
