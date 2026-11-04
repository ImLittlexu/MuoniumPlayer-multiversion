package com.muoniumplayer.party;

import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.music.BvRoomAudio;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.GogoAudioResolver;
import com.muoniumplayer.core.ncm.music.GogoMusicService;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.dto.Music;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** 房间会话内保存定位；元数据队列和 url_needed 都不保证带回原 URL。 */
final class BvRoomTracks {
  private static final AtomicLong GENERATION = new AtomicLong();
  private static final Map<String, BvRoomAudio.Locator> LOCATORS = new HashMap<>();
  private static String roomKey = "";
  static final ExecutorService PREPARERS =
      Executors.newFixedThreadPool(
          2,
          runnable -> {
            Thread thread = new Thread(runnable, "mp-bv-prepare");
            thread.setDaemon(true);
            return thread;
          });

  private BvRoomTracks() {}

  static synchronized void enter(String key) {
    if (!roomKey.equals(key)) {
      clear();
      roomKey = key;
    }
  }

  static synchronized void clear() {
    GENERATION.incrementAndGet();
    LOCATORS.clear();
    roomKey = "";
  }

  static long session() {
    return GENERATION.get();
  }

  static boolean valid(long token) {
    return token == session() && MpPartyClient.inRoom();
  }

  static boolean supports(String source) {
    MusicPlatform platform = MpTrack.platformOf(source);
    return platform != null && platform.isBvLibrary();
  }

  static synchronized void observe(MpTrack track) {
    if (track == null || !supports(track.source) || track.numericId() <= 0) return;
    BvRoomAudio.Locator locator = BvRoomAudio.fromUrl(track.url);
    if (locator != null)
      LOCATORS.put(track.platform().name() + ':' + track.songId + ':' + track.entryId, locator);
  }

  private static String key(Music song, long entryId) {
    return song.getSource().name() + ':' + song.getSourceId() + ':' + entryId;
  }

  static synchronized void restore(MpTrack track, Music song) {
    if (!song.isBvLibrary()) return;
    BvRoomAudio.Locator locator = BvRoomAudio.fromUrl(track.url);
    String key = key(song, track.entryId);
    if (locator == null) locator = LOCATORS.get(key);
    if (locator != null) locator.apply(song);
    song.setPartyExpectedDurationMs(track.durationMs);
    bind(song, track.entryId, session());
  }

  static void bind(Music song, long entryId, long token) {
    song.setPartySessionValid(() -> valid(token) && locatorMatches(song, entryId));
  }

  private static synchronized boolean locatorMatches(Music song, long entryId) {
    BvRoomAudio.Locator now = LOCATORS.get(key(song, entryId));
    return now == null
        || song.getGogoBvid().isEmpty()
        || (now.bvid.equals(song.getGogoBvid()) && now.cid.equals(song.getGogoCid()));
  }

  /** 只在后台调用。新 next/play 的 URL 优先于稍早的详情查询。 */
  static void locate(Music song, long entryId, long token) throws IOException {
    if (!valid(token)) throw new CancellationException();
    synchronized (BvRoomTracks.class) {
      BvRoomAudio.Locator known = LOCATORS.get(key(song, entryId));
      if (known != null) known.apply(song);
    }
    GogoMusicService.ensureLocator(song);
    synchronized (BvRoomTracks.class) {
      if (!valid(token)) throw new CancellationException();
      BvRoomAudio.Locator known = LOCATORS.get(key(song, entryId));
      if (known != null) known.apply(song);
      else
        LOCATORS.put(
            key(song, entryId), new BvRoomAudio.Locator(song.getGogoBvid(), song.getGogoCid()));
    }
    bind(song, entryId, token);
  }

  interface DurationLookup {
    long duration(Music song) throws Exception;
  }

  static final class Prepared {
    final Music song;
    final BvRoomAudio.Locator locator;
    final long duration, expiresAt;

    Prepared(Music song, BvRoomAudio.Locator locator, long duration, long expiresAt) {
      this.song = song;
      this.locator = locator;
      this.duration = duration;
      this.expiresAt = expiresAt;
    }

    JsonObject json() {
      JsonObject json = MpTrack.toJson(song, locator.url(), expiresAt, 0L);
      json.addProperty("duration", duration);
      return json;
    }
  }

  static Prepared prepare(Music original, long entryId, long token) throws Exception {
    return prepare(original, entryId, token, 0L, false);
  }

  static Prepared prepare(Music original, long entryId, long token, long deadline)
      throws Exception {
    return prepare(original, entryId, token, deadline, true);
  }

  private static Prepared prepare(
      Music original, long entryId, long token, long deadline, boolean renewal) throws Exception {
    Music song = original.queueIdentityCopy();
    locate(song, entryId, token);
    java.util.function.BooleanSupplier valid = song.getPartySessionValid();
    song.setPartySessionValid(
        () -> valid.getAsBoolean() && (deadline <= 0 || System.currentTimeMillis() < deadline));
    if (song.roomAudioCancelled()) throw new CancellationException();
    // 续期的固定公开 URL 不需要重新签名，已有房间时长和冻结定位即可立即应答。
    if (renewal && original.getDuration() > 0) {
      return prepareLocated(
          song,
          track -> original.getDuration(),
          track -> {
            throw new IOException("房间时长无效");
          });
    }
    return prepareLocated(
        song, GogoAudioResolver::roomDuration, CloudMusic::prepareBvRoomTrackDuration);
  }

  static Prepared prepareLocated(Music song, DurationLookup metadata, DurationLookup decoded)
      throws Exception {
    long duration;
    try {
      duration = BvRoomAudio.requireDuration(metadata.duration(song));
    } catch (Exception failure) {
      if (Thread.currentThread().isInterrupted() || song.roomAudioCancelled())
        throw new CancellationException();
      duration = BvRoomAudio.requireDuration(decoded.duration(song));
    }
    if (Thread.currentThread().isInterrupted() || song.roomAudioCancelled())
      throw new CancellationException();
    return new Prepared(
        song,
        new BvRoomAudio.Locator(song.getGogoBvid(), song.getGogoCid()),
        duration,
        System.currentTimeMillis() + 18 * 60 * 1000L);
  }
}
