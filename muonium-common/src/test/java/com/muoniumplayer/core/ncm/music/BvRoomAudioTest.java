package com.muoniumplayer.core.ncm.music;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sound.sampled.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BvRoomAudioTest {
  @TempDir Path temp;

  @Test
  void exactProxyLocatorRoundTripsLargeCidAndRejectsOtherOrigins() {
    String url = new BvRoomAudio.Locator("BV1ETsX61EyX", "43133044682").url();
    assertEquals("43133044682", BvRoomAudio.fromUrl(url).cid);
    for (String invalid :
        List.of(
            url.replace("https://", "http://"),
            url.replace("/proxy?", "/proxy/extra?"),
            url.replace(BvRoomAudio.PROXY_HOST, BvRoomAudio.PROXY_HOST + ".evil.test"),
            url.replace("/proxy?", ":443/proxy?"),
            url + "&cid=1",
            url + "#anything",
            url + "&x=1",
            url.replace("BV1ETsX61EyX", "BVbad"),
            url.replace("43133044682", "0"),
            url.replace("43133044682", "-1"),
            url.replace("43133044682", "4%31"),
            "muonium-gogo://song/481")) {
      assertNull(BvRoomAudio.fromUrl(invalid), invalid);
    }
    assertNull(BvRoomAudio.fromUrl(null));
  }

  @Test
  void durationUsesExactTimelengthAndRoomLimit() throws Exception {
    assertEquals(
        218254,
        BvRoomAudio.duration(
            JsonParser.parseString(
                    "{\"code\":0,\"data\":{\"timelength\":218254,\"dash\":{\"duration\":219}}}")
                .getAsJsonObject()));
    for (String value : List.of("0", "-1", "218.2", "1800001", "null", "999999999999999999999"))
      assertThrows(
          IOException.class,
          () ->
              BvRoomAudio.duration(
                  JsonParser.parseString("{\"code\":0,\"data\":{\"timelength\":" + value + "}}")
                      .getAsJsonObject()));
    assertThrows(
        IOException.class,
        () ->
            BvRoomAudio.duration(
                JsonParser.parseString("{\"code\":-400,\"data\":{\"timelength\":218254}}")
                    .getAsJsonObject()));
    assertEquals(1800000, BvRoomAudio.requireDuration(1800000));
  }

  @Test
  void decodedWavDurationHasOneSecondToleranceOnlyInRooms() throws Exception {
    File source = temp.resolve("audio.m4a").toFile();
    File wav = AudioCacheFiles.getDecodedWavFile(source);
    Files.createDirectories(wav.getParentFile().toPath());
    try {
      AudioFormat format = new AudioFormat(48000, 16, 2, true, false);
      try (AudioInputStream input =
          new AudioInputStream(new ByteArrayInputStream(new byte[48000 * 4]), format, 48000)) {
        AudioSystem.write(input, AudioFileFormat.Type.WAVE, wav);
      }
      assertEquals(1000, BvRoomAudio.decodedDuration(source));
      var song = GogoMusicTest.song();
      BvRoomAudio.validateDuration(source, song);
      song.setPartyExpectedDurationMs(2000);
      BvRoomAudio.validateDuration(source, song);
      song.setPartyExpectedDurationMs(2001);
      assertThrows(IOException.class, () -> BvRoomAudio.validateDuration(source, song));
    } finally {
      Files.deleteIfExists(wav.toPath());
    }
  }

  @Test
  void cachedDurationPreparationUsesSharedKeyAndRoomCacheChecksActualAudio() throws Exception {
    var record = GogoMusicTest.record("43133044682");
    record.addProperty("id", UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE);
    var song = GogoMusicService.song(record);
    Class<?> player = Class.forName(CloudMusic.class.getName() + "$PlayThread");
    var keyMethod = player.getDeclaredMethod("audioCacheKey", song.getClass(), String.class);
    keyMethod.setAccessible(true);
    String key = (String) keyMethod.invoke(null, song, CloudMusic.quality.getQuality());
    File audio = new File(MusicAudioCache.directory(), key + ".mp4");
    File wav = AudioCacheFiles.getDecodedWavFile(audio);
    Files.createDirectories(audio.getParentFile().toPath());
    try {
      Files.write(audio.toPath(), new byte[] {0, 0, 0, 8, 'f', 't', 'y', 'p'});
      AudioFormat format = new AudioFormat(48000, 16, 2, true, false);
      try (AudioInputStream input =
          new AudioInputStream(new ByteArrayInputStream(new byte[48000 * 4]), format, 48000)) {
        AudioSystem.write(input, AudioFileFormat.Type.WAVE, wav);
      }
      assertNotNull(CloudMusic.cachedAudioFileFor(song));
      assertEquals(1000, CloudMusic.prepareBvRoomTrackDuration(song));
      assertFalse(CloudMusic.quietDownload());
      song.setPartyExpectedDurationMs(2001);
      assertNull(CloudMusic.cachedAudioFileFor(song));
      assertTrue(audio.isFile(), "只读缓存探测不能删除普通播放缓存");
      song.setPartyExpectedDurationMs(2000);
      assertNotNull(CloudMusic.cachedAudioFileFor(song));
    } finally {
      Files.deleteIfExists(audio.toPath());
      Files.deleteIfExists(wav.toPath());
    }
  }

  @Test
  void wrongRoomDurationAdvancesRoutesAndSessionCancellationStopsFallback() throws Exception {
    for (MusicPlatform platform : List.of(MusicPlatform.GOGO, MusicPlatform.UNM)) {
      var song = GogoMusicService.song(GogoMusicTest.record("43133044682"), platform);
      song.setPartyExpectedDurationMs(218254);
      List<String> attempted = new ArrayList<>();
      AtomicInteger validation = new AtomicInteger();
      GogoAudioResolver.prepare(
          song,
          temp.toFile(),
          platform.name(),
          s -> List.of("https://direct/a"),
          (url, target, s) -> {
            attempted.add(url);
            Files.write(target.toPath(), new byte[] {0, 0, 0, 8, 'f', 't', 'y', 'p'});
          },
          (file, s) ->
              BvRoomAudio.checkDuration(
                  s.getPartyExpectedDurationMs(),
                  validation.incrementAndGet() == 1 ? 30000 : 218254));
      assertEquals(2, attempted.size());
      assertEquals(platform == MusicPlatform.GOGO ? "R2" : "代理", song.getGogoAudioRoute());
      AtomicInteger transferred = new AtomicInteger();
      song.setPartySessionValid(() -> true);
      assertThrows(
          IllegalStateException.class,
          () ->
              GogoAudioResolver.prepare(
                  song,
                  temp.toFile(),
                  "cancel",
                  s -> List.of("https://direct/a"),
                  (url, file, s) -> {
                    transferred.incrementAndGet();
                    s.setPartySessionValid(() -> false);
                    throw new IOException("left room");
                  },
                  (file, s) -> {}));
      assertEquals(1, transferred.get());
    }
  }
}
