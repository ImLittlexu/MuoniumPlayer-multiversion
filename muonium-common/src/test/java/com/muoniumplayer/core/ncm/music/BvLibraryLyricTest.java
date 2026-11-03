package com.muoniumplayer.core.ncm.music;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.screens.ncm.LyricParser;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BvLibraryLyricTest {
  private static JsonObject lyrics() {
    return GogoMusicTest.json(
        "{\"available\":true,\"songId\":481,\"format\":\"LRC\",\"syncMode\":\"LINE\","
            + "\"sourceType\":\"BILIBILI_AI_SUBTITLE\",\"qualityStatus\":\"AI_GENERATED\","
            + "\"disclaimer\":\"AI 测试提示\",\"versionId\":12,\"lines\":["
            + "{\"startMs\":3000,\"endMs\":4100,\"text\":\"第二行\"},"
            + "{\"startMs\":1000,\"text\":\"第一行\"},"
            + "{\"startMs\":5000,\"text\":\"最后一行\"}]}");
  }

  @Test
  void preservesMillisecondTimingAndInfersOnlyMissingEndsWithoutInventingWords() throws Exception {
    var result = BvLibraryLyricService.parse(lyrics(), "481", 7000L);
    assertEquals(
        List.of(1000L, 3000L, 5000L), result.lines.stream().map(line -> line.timestamp).toList());
    assertEquals(
        List.of(2000L, 1100L, 2000L), result.lines.stream().map(line -> line.duration).toList());
    assertEquals("第一行", result.lines.get(0).lyric);
    assertFalse(LyricParser.hasRealWordTiming(result.lines));
    assertTrue(result.lines.stream().allMatch(line -> line.words.isEmpty()));
    var metadata = result.rawJson.getAsJsonObject(BvLibraryLyricService.METADATA);
    assertTrue(BvLibraryLyricService.isAi(metadata));
    assertEquals("AI 测试提示", metadata.get("disclaimer").getAsString());
    assertEquals(12, metadata.get("versionId").getAsInt());
  }

  @Test
  void absentOrEmptyLyricsAllowExistingMatchingFallback() throws Exception {
    var unavailable = GogoMusicTest.json("{\"available\":false,\"songId\":481,\"reason\":\"冷却中\"}");
    assertTrue(BvLibraryLyricService.parse(unavailable, "481", 0).lines.isEmpty());
    JsonObject empty = lyrics();
    empty.add("lines", GogoMusicTest.json("{\"lines\":[]}").get("lines"));
    assertTrue(BvLibraryLyricService.parse(empty, "481", 0).lines.isEmpty());
  }

  @Test
  void rejectsWrongSongUnsupportedFormatAndMalformedAvailability() {
    JsonObject wrong = lyrics();
    wrong.addProperty("songId", 482);
    assertThrows(IOException.class, () -> BvLibraryLyricService.parse(wrong, "481", 0));
    for (String field : List.of("format", "syncMode", "available")) {
      JsonObject invalid = lyrics();
      invalid.addProperty(field, "unsupported");
      assertThrows(IOException.class, () -> BvLibraryLyricService.parse(invalid, "481", 0));
    }
  }

  @Test
  void rejectsNegativeFractionalOverflowAndReversedTimesInsteadOfShowingBadTimeline() {
    for (String time : List.of("-1", "1.5", "9223372036854775808", "null", "\"1000\"")) {
      JsonObject invalid = lyrics();
      invalid
          .getAsJsonArray("lines")
          .get(0)
          .getAsJsonObject()
          .add("startMs", com.google.gson.JsonParser.parseString(time));
      assertThrows(IOException.class, () -> BvLibraryLyricService.parse(invalid, "481", 0));
    }
    JsonObject invalid = lyrics();
    invalid.getAsJsonArray("lines").get(0).getAsJsonObject().addProperty("endMs", 1000);
    assertThrows(IOException.class, () -> BvLibraryLyricService.parse(invalid, "481", 0));
  }

  @Test
  void completesOpenFinalLineWhenDecodedAudioDurationBecomesKnown() throws Exception {
    var result = BvLibraryLyricService.parse(lyrics(), "481", 0);
    assertEquals(0L, result.lines.get(2).duration);
    BvLibraryLyricService.completeLastLine(result.rawJson, result.lines, 8100);
    assertEquals(3100L, result.lines.get(2).duration);
    assertEquals(1100L, result.lines.get(1).duration);
    JsonObject explicit = lyrics();
    explicit.getAsJsonArray("lines").get(2).getAsJsonObject().addProperty("endMs", 5500);
    var fixed = BvLibraryLyricService.parse(explicit, "481", 9000);
    BvLibraryLyricService.completeLastLine(fixed.rawJson, fixed.lines, 10000);
    assertEquals(500L, fixed.lines.get(2).duration);
  }

  @Test
  void manualLyricsRemainLineTimedAndHaveNoAiDisclaimer() throws Exception {
    JsonObject manual = lyrics();
    manual.addProperty("sourceType", "MANUAL");
    manual.addProperty("qualityStatus", "VERIFIED");
    manual.remove("disclaimer");
    var result = BvLibraryLyricService.parse(manual, "481", 7000);
    assertFalse(
        BvLibraryLyricService.isAi(result.rawJson.getAsJsonObject(BvLibraryLyricService.METADATA)));
    assertFalse(LyricParser.hasRealWordTiming(result.lines));
    assertEquals(3, result.lines.size());
  }

  @Test
  void fetchesPublicLyricsByBusinessIdWithExistingEnvelopeHandlingForBothPlatforms()
      throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicReference<String> authorization = new AtomicReference<>();
    server.createContext(
        "/api/songs/481/lyrics",
        exchange -> {
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] response =
              ("{\"success\":true,\"code\":2000,\"data\":" + lyrics() + "}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          try (var body = exchange.getResponseBody()) {
            body.write(response);
          }
        });
    server.start();
    try {
      GogoApiClient api = new GogoApiClient("http://127.0.0.1:" + server.getAddress().getPort());
      for (MusicPlatform platform : List.of(MusicPlatform.GOGO, MusicPlatform.UNM)) {
        Music music = GogoMusicService.song(GogoMusicTest.record("43133044682"), platform);
        assertEquals(3, BvLibraryLyricService.fetch(music, api).lines.size());
        assertNull(authorization.get());
      }
    } finally {
      server.stop(0);
    }
  }

  @Test
  void cachedNativeLyricsKeepAiAttributionAndRestorePlatformSource() throws Exception {
    for (MusicPlatform platform : List.of(MusicPlatform.GOGO, MusicPlatform.UNM)) {
      Music music = GogoMusicService.song(GogoMusicTest.record("43133044682"), platform);
      String key = LyricLoadService.cacheKey(music.getStableKey());
      var result = BvLibraryLyricService.parse(lyrics(), "481", 7000);
      try {
        LyricCacheStore.store(key, result.lines, result.rawJson);
        var loaded = LyricLoadService.load(music, music.getId(), 0, null);
        assertEquals(3, loaded.lines.size());
        assertEquals(result.rawJson, loaded.rawJson);
        assertEquals(
            LyricSourceMemory.PLATFORM, LyricLoadService.servedSource(music.getStableKey()));
        assertFalse(LyricParser.hasRealWordTiming(loaded.lines));
      } finally {
        LyricCacheStore.invalidate(key);
      }
    }
  }

  @Test
  void malformedSourceMetadataFallsBackBeforeReachingTheRenderer() throws Exception {
    JsonObject malformed = lyrics();
    malformed.add("disclaimer", new JsonObject());
    assertThrows(IOException.class, () -> BvLibraryLyricService.parse(malformed, "481", 0));
    String key = "probe:library-lyrics:bad-metadata";
    try {
      var result = BvLibraryLyricService.parse(lyrics(), "481", 7000);
      result
          .rawJson
          .getAsJsonObject(BvLibraryLyricService.METADATA)
          .add("sourceType", new JsonObject());
      LyricCacheStore.store(key, result.lines, result.rawJson);
      assertTrue(LyricCacheStore.loadResult(key, Long.MAX_VALUE).lines.isEmpty());
    } finally {
      LyricCacheStore.invalidate(key);
    }
  }

  @Test
  void nativeCacheExpiresWithoutExpiringOtherPlatformsOrReusingLegacyKeys() throws Exception {
    assertTrue(LyricLoadService.cacheKey("gogo_481").endsWith("|lyrics1"));
    assertTrue(LyricLoadService.cacheKey("unm_481").endsWith("|lyrics1"));
    assertFalse(LyricLoadService.cacheKey("netease_481").contains("lyrics1"));
    assertNotEquals(LyricLoadService.cacheKey("gogo_481"), LyricLoadService.cacheKey("unm_481"));
    String key = "probe:library-lyrics:expired";
    try {
      var result = BvLibraryLyricService.parse(lyrics(), "481", 7000);
      LyricCacheStore.store(key, result.lines, result.rawJson);
      var method = LyricCacheStore.class.getDeclaredMethod("fileFor", String.class);
      method.setAccessible(true);
      File file = (File) method.invoke(null, key);
      assertTrue(
          file.setLastModified(
              System.currentTimeMillis() - BvLibraryLyricService.CACHE_MAX_AGE_MS - 1000));
      assertTrue(
          LyricCacheStore.loadResult(key, BvLibraryLyricService.CACHE_MAX_AGE_MS).lines.isEmpty());
      assertEquals(3, LyricCacheStore.load(key).size());
    } finally {
      LyricCacheStore.invalidate(key);
    }
  }
}
