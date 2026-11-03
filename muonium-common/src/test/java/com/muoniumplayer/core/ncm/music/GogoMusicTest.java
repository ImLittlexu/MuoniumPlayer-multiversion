package com.muoniumplayer.core.ncm.music;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.muoniumplayer.core.ncm.music.dto.Music;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GogoMusicTest {
  @TempDir Path temp;

  static JsonObject json(String text) {
    return JsonParser.parseString(text).getAsJsonObject();
  }

  static JsonObject record(String cid) {
    JsonObject song =
        json(
            "{\"id\":481,\"songName\":\"秋天别来\",\"originalArtist\":[\"侯湘婷\"],\"bv\":\"BV1ETsX61EyX\",\"coverUrl\":null}");
    song.addProperty("cid", cid);
    return song;
  }

  static Music song() throws IOException {
    return GogoMusicService.song(record("43133044682"));
  }

  @Test
  void mappingAndRoundTripKeepPlatformAndLargeCid() throws Exception {
    Music original = song();
    Music restored =
        LocalPlaylistStore.deserializeTrack(LocalPlaylistStore.serializeTrack(original));
    assertNotNull(restored);
    assertEquals(MusicPlatform.GOGO, restored.getSource());
    assertEquals("481", restored.getSourceId());
    assertEquals("43133044682", restored.getGogoCid());
    assertEquals(original.getStableKey(), restored.getStableKey());
    assertEquals(original.getGogoBvid(), original.queueIdentityCopy().getGogoBvid());
    assertFalse(restored.isNetease());
    assertFalse(CrossSourceSwitcher.canAutoSwitch(restored));
    assertTrue(restored.getPlayUrl().getA().startsWith(GogoAudioResolver.PREFIX));
    assertEquals("侯湘婷", restored.getArtistsName());
    assertEquals("", original.getExternalCoverUrl());
  }

  @Test
  void numericCidAndDifferentPartsHaveDistinctCacheLocators() throws Exception {
    JsonObject numeric = record("1");
    numeric.addProperty("cid", 43133044682L);
    Music a = GogoMusicService.song(numeric), b = GogoMusicService.song(record("43133044683"));
    assertEquals("43133044682", a.getGogoCid());
    assertNotEquals(GogoAudioResolver.locatorKey(a), GogoAudioResolver.locatorKey(b));
    assertThrows(IOException.class, () -> GogoMusicService.song(record("0")));
    assertThrows(IOException.class, () -> GogoMusicService.song(record("4.2")));
  }

  @Test
  void envelopesAndPaginationDistinguishErrorsFromEmptyResults() throws Exception {
    assertThrows(
        IOException.class,
        () -> GogoApiClient.unwrap("{\"success\":false,\"code\":4002,\"message\":\"拒绝\"}"));
    assertThrows(IOException.class, () -> GogoApiClient.unwrap("{\"success\":true,\"code\":5000}"));
    assertThrows(IOException.class, () -> GogoApiClient.unwrap("<html>error</html>"));
    GogoMusicService.Page<Music> page =
        GogoMusicService.parseSongs(
            json("{\"items\":[],\"pagination\":{\"page\":2,\"total\":20,\"totalPages\":2}}"));
    assertTrue(page.items.isEmpty());
    assertEquals(2, page.page);
    assertEquals(2, page.totalPages);
    assertThrows(IOException.class, () -> GogoMusicService.parseSongs(json("{}")));
  }

  @Test
  void playlistOrderFollowsSortOrderRatherThanWireOrder() throws Exception {
    JsonObject data = new JsonObject();
    JsonArray entries = new JsonArray();
    data.add("songs", entries);
    JsonObject later = new JsonObject();
    later.addProperty("sortOrder", 2);
    later.add("song", record("2"));
    JsonObject earlier = new JsonObject();
    earlier.addProperty("sortOrder", 1);
    earlier.add("song", record("1"));
    entries.add(later);
    entries.add(earlier);
    List<Music> songs = GogoMusicService.parsePlaylistSongs(data);
    assertEquals("1", songs.get(0).getGogoCid());
    assertEquals("2", songs.get(1).getGogoCid());
  }

  @Test
  void apiEncodesChineseQueryAndChecksHttpErrors() throws Exception {
    com.sun.net.httpserver.HttpServer server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    List<String> queries = new ArrayList<>();
    server.createContext(
        "/api/songs",
        exchange -> {
          queries.add(exchange.getRequestURI().getRawQuery());
          byte[] body =
              "{\"success\":true,\"code\":2000,\"data\":{}}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext(
        "/bad",
        exchange -> {
          exchange.sendResponseHeaders(403, -1);
          exchange.close();
        });
    server.start();
    try {
      GogoApiClient api = new GogoApiClient("http://127.0.0.1:" + server.getAddress().getPort());
      api.get("/api/songs", Map.of("keyword", "秋天 & A"));
      assertEquals("keyword=" + GogoApiClient.encode("秋天 & A"), queries.get(0));
      assertThrows(IOException.class, () -> api.get("/bad", Map.of()));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void aacSelectionPrefers30280AndReadsBothUrlSpellings() throws Exception {
    JsonObject root =
        json(
            "{\"code\":0,\"data\":{\"dash\":{\"audio\":["
                + "{\"id\":30216,\"codecs\":\"mp4a.40.2\",\"bandwidth\":900000,\"baseUrl\":\"https://lower/a\"},"
                + "{\"id\":30280,\"codecs\":\"mp4a.40.2\",\"base_url\":\"https://main/a\",\"backup_url\":[\"https://backup/a\",\"https://main/a\"]},"
                + "{\"id\":30251,\"codecs\":\"fLaC\",\"baseUrl\":\"https://flac/a\"}]}}}");
    assertEquals(
        List.of("https://main/a", "https://backup/a"), GogoAudioResolver.parseDirectUrls(root));
    root.getAsJsonObject("data").getAsJsonObject("dash").getAsJsonArray("audio").remove(1);
    assertEquals(List.of("https://lower/a"), GogoAudioResolver.parseDirectUrls(root));
  }

  @Test
  void transferAndDecodeFailuresAdvanceThroughBackupR2AndProxy() throws Exception {
    List<String> seen = new ArrayList<>();
    AtomicInteger validations = new AtomicInteger();
    File result =
        GogoAudioResolver.prepare(
            song(),
            temp.toFile(),
            "audio",
            s -> List.of("https://main/a", "https://backup/a"),
            (url, file, s) -> {
              seen.add(url);
              if (url.contains("main")) throw new IOException("403");
              writeWav(file);
            },
            (file, s) -> {
              if (validations.incrementAndGet() < 3) throw new IOException("bad audio");
            });
    assertTrue(result.isFile());
    assertEquals(4, seen.size());
    assertTrue(seen.get(2).contains("cdn.eoes.cc/gogo/music/BV1ETsX61EyX_43133044682.m4a"));
    assertTrue(seen.get(3).contains("bvid=BV1ETsX61EyX&cid=43133044682"));
    assertFalse(new File(temp.toFile(), "audio.candidate.mp4").exists());
  }

  @Test
  void directApiFailureFallsBackAndEmptyFilesAreRejected() throws Exception {
    List<String> seen = new ArrayList<>();
    assertThrows(
        IllegalStateException.class,
        () ->
            GogoAudioResolver.prepare(
                song(),
                temp.toFile(),
                "empty",
                s -> {
                  throw new IOException("api failed");
                },
                (url, file, s) -> {
                  seen.add(url);
                  Files.write(file.toPath(), new byte[0]);
                },
                (f, s) -> {}));
    assertEquals(2, seen.size());
    assertTrue(seen.get(0).contains("cdn.eoes.cc"));
    assertFalse(new File(temp.toFile(), "empty.candidate.mp4").exists());
  }

  @Test
  void cancellationNeverStartsAnotherCandidate() throws Exception {
    AtomicInteger transfers = new AtomicInteger();
    try {
      assertThrows(
          IllegalStateException.class,
          () ->
              GogoAudioResolver.prepare(
                  song(),
                  temp.toFile(),
                  "cancel",
                  s -> List.of("https://one/a"),
                  (url, file, s) -> {
                    transfers.incrementAndGet();
                    Thread.currentThread().interrupt();
                    throw new IOException("cancelled");
                  },
                  (f, s) -> {}));
      assertEquals(1, transfers.get());
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void countingUsesThirtySecondsStrictlyAndOnlyOnce() {
    GogoPlaybackReporter.Counter c = new GogoPlaybackReporter.Counter(0, 0);
    for (int i = 1; i <= 30; i++) assertFalse(c.observe(i * 1_000_000_000L, i * 1000, true));
    assertTrue(c.observe(30_100_000_000L, 30100, true));
    assertFalse(c.observe(31_000_000_000L, 31000, true));
  }

  @Test
  void pauseSeekAndStallDoNotSupplyPlaybackTime() {
    GogoPlaybackReporter.Counter c = new GogoPlaybackReporter.Counter(0, 0);
    for (int i = 1; i <= 20; i++) assertFalse(c.observe(i * 1_000_000_000L, i * 1000, true));
    assertFalse(c.observe(21_000_000_000L, 20000, false));
    assertFalse(c.observe(22_000_000_000L, 100000, true));
    for (int i = 23; i <= 50; i++) assertFalse(c.observe(i * 1_000_000_000L, 100000, true));
    for (int i = 51; i <= 80; i++)
      assertFalse(c.observe(i * 1_000_000_000L, 100000 + (i - 50) * 1000, true));
    assertTrue(c.observe(81_000_000_000L, 131000, true));
    GogoPlaybackReporter.Counter replay = new GogoPlaybackReporter.Counter(0, 0);
    for (int i = 1; i <= 30; i++) assertFalse(replay.observe(i * 1_000_000_000L, i * 1000, true));
    assertTrue(replay.observe(31_000_000_000L, 31000, true));
  }

  private static void writeWav(File file) throws IOException {
    byte[] bytes = new byte[48];
    System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 4);
    System.arraycopy("WAVE".getBytes(StandardCharsets.US_ASCII), 0, bytes, 8, 4);
    Files.write(file.toPath(), bytes);
  }
}
