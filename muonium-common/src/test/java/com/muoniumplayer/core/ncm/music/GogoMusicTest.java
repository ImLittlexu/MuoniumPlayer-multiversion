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

  @Test
  @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "MUONIUM_IMPORT_LIVE", matches = "1")
  void liveAnonymousPlaylistSnapshots() throws Exception {
    for (MusicPlatform platform : List.of(MusicPlatform.GOGO, MusicPlatform.UNM)) {
      var lists = GogoMusicService.playlists(platform, "", "hot", 1);
      assertFalse(lists.items.isEmpty());
      var snapshot = GogoMusicService.playlistSnapshot(platform, lists.items.get(0).getPlatformPlaylistId());
      assertTrue(snapshot.musicsLoaded);
      assertEquals(snapshot.getCount(), snapshot.getMusics().size());
      System.out.println(platform.name() + " ordinary snapshot tracks=" + snapshot.getCount());
    }
    String link = System.getenv("GOGO_FAVORITES_PROBE_LINK");
    assertNotNull(link, "请通过环境变量提供收藏分享链接");
    var favorites = PlaylistImportService.importPlaylist(link);
    assertEquals(MusicPlatform.GOGO, favorites.getPlatform());
    assertTrue(favorites.musicsLoaded);
    assertFalse(favorites.getMusics().isEmpty());
    System.out.println("GOGO favorites snapshot tracks=" + favorites.getCount());
    String unmFavorites = System.getenv("UNM_FAVORITES_PROBE_LINK");
    if (unmFavorites != null) {
      String unmPlaylist = System.getenv("UNM_PLAYLIST_PROBE_LINK");
      assertNotNull(unmPlaylist, "请同时提供 UNM 公开歌单链接");
      for (String unmLink : List.of(unmPlaylist, unmFavorites)) {
        var snapshot = PlaylistImportService.importPlaylist(unmLink);
        assertEquals(MusicPlatform.UNM, snapshot.getPlatform());
        assertTrue(snapshot.musicsLoaded);
        assertFalse(snapshot.getMusics().isEmpty());
        assertEquals(snapshot.getCount(), snapshot.getMusics().size());
        assertFalse(snapshot.getName().isBlank());
        for (Music track : snapshot.getMusics()) {
          assertEquals(MusicPlatform.UNM, track.getSource());
          assertTrue(GogoMusicService.validLocator(track.getGogoBvid(), track.getGogoCid()));
        }
        System.out.println("UNM " + (snapshot.getPlatformPlaylistId().startsWith("favorites:")
            ? "favorites" : "ordinary") + " shared snapshot tracks=" + snapshot.getCount());
      }
    }
  }

  @Test
  void importLinksRecognizeBothSitesAndPreserveTokenCase() {
    for (String domain : List.of("ggd.fan", "unm.fan")) {
      MusicPlatform platform = domain.equals("ggd.fan") ? MusicPlatform.GOGO : MusicPlatform.UNM;
      for (String link : List.of("https://" + domain + "/playlist/28/",
          "https://api." + domain + "/playlist/28/",
          "https://api." + domain + "/api/playlists/28?from=share#top")) {
        var found = PlaylistImportService.detect("BV1ETsX61EyX 分享 " + link);
        assertEquals(platform, found.platform);
        assertEquals("28", found.cleaned);
      }
      for (String link : List.of("https://" + domain + "/share/favorites/AbC_-123/",
          "https://api." + domain + "/api/shares/favorites/AbC_-123?from=share")) {
        var found = PlaylistImportService.detect(link);
        assertEquals(platform, found.platform);
        assertEquals("favorites:AbC_-123", found.cleaned);
      }
    }
    for (String link : List.of("https://ggd.fan.evil.test/playlist/28",
        "https://ggd.fan/playlist/0", "https://ggd.fan/playlist/9223372036854775808",
        "https://ggd.fan/share/favorites/a%2Fb", "https://unm.fan/share/favorites/",
        "https://ggd.fan@evil.test/playlist/28", "https://ggd.fan:123/playlist/28"))
      assertThrows(IllegalArgumentException.class, () -> PlaylistImportService.detect(link), link);
  }

  private static JsonObject favorites() {
    JsonObject data = json("{\"title\":\"收藏测试\",\"coverUrl\":\"\",\"items\":[],"
        + "\"pagination\":{\"page\":1,\"total\":2,\"totalPages\":1}}");
    JsonObject first = record("11");
    first.addProperty("coverUrl", "http://example.test/cover.png");
    data.getAsJsonArray("items").add(first);
    data.getAsJsonArray("items").add(record("12"));
    return data;
  }

  @Test
  void snapshotsKeepOrderIdentityAndRoundTripOnlyReferences() throws Exception {
    for (MusicPlatform platform : List.of(MusicPlatform.GOGO, MusicPlatform.UNM)) {
      var snapshot = GogoMusicService.parsePlaylistSnapshot(favorites(), platform, "favorites:AbC_123");
      assertEquals("收藏测试", snapshot.getName());
      assertEquals("https://example.test/cover.png", snapshot.getCoverUrl());
      assertEquals(2, snapshot.getCount());
      assertEquals("11", snapshot.getMusics().get(0).getGogoCid());
      assertEquals("12", snapshot.getMusics().get(1).getGogoCid());
      assertTrue(snapshot.musicsLoaded && snapshot.musicsQueried);
      assertEquals(platform, snapshot.getMusics().get(0).getSource());
      var serialize = ImportedPlaylistStore.class.getDeclaredMethod("serialize", snapshot.getClass());
      var deserialize = ImportedPlaylistStore.class.getDeclaredMethod("deserialize", JsonObject.class);
      serialize.setAccessible(true);
      deserialize.setAccessible(true);
      JsonObject stored = (JsonObject) serialize.invoke(null, snapshot);
      assertFalse(stored.has("items") || stored.has("songs") || stored.has("musics"));
      var restored = (com.muoniumplayer.core.ncm.music.dto.PlayList) deserialize.invoke(null, stored);
      assertEquals(ImportedPlaylistStore.keyOf(snapshot), ImportedPlaylistStore.keyOf(restored));
      assertEquals(snapshot.getId(), restored.getId());
      assertEquals("/api/shares/favorites/AbC_123", GogoMusicService.playlistPath(restored.getPlatformPlaylistId()));
    }
    assertNotEquals(GogoMusicService.parsePlaylistSnapshot(favorites(), MusicPlatform.GOGO, "favorites:AbC_123").getId(),
        GogoMusicService.parsePlaylistSnapshot(favorites(), MusicPlatform.UNM, "favorites:AbC_123").getId());
  }

  @Test
  void emptyMalformedIncompleteAndMismatchedSnapshotsAreDistinct() throws Exception {
    var empty = GogoMusicService.parsePlaylistSnapshot(json("{\"items\":[]}"), MusicPlatform.GOGO, "favorites:Empty");
    assertEquals("GOGO娱乐收藏", empty.getName());
    assertTrue(empty.getMusics().isEmpty());
    for (String body : List.of("{}", "{\"items\":null}", "{\"items\":{}}",
        "{\"items\":[],\"pagination\":{\"total\":10,\"totalPages\":2}}"))
      assertThrows(IOException.class, () -> GogoMusicService.parsePlaylistSnapshot(json(body), MusicPlatform.GOGO, "favorites:A"));
    JsonObject broken = favorites();
    broken.getAsJsonArray("items").get(0).getAsJsonObject().remove("bv");
    assertThrows(IOException.class, () -> GogoMusicService.parsePlaylistSnapshot(broken, MusicPlatform.GOGO, "favorites:A"));
    assertThrows(IOException.class, () -> GogoMusicService.parsePlaylistSnapshot(json("{\"id\":2,\"songs\":[]}"), MusicPlatform.UNM, "1"));
    var ordinary = GogoMusicService.parsePlaylistSnapshot(json("{\"id\":1,\"songs\":[]}"), MusicPlatform.UNM, "1");
    assertEquals(1L, ordinary.getId());
    assertEquals("UNM小站歌单", ordinary.getName());
  }

  @Test
  void refreshCreatesIndependentSnapshotsAndStaleReferencesCannotBeRestored() throws Exception {
    var original = GogoMusicService.parsePlaylistSnapshot(favorites(), MusicPlatform.GOGO, "favorites:Test");
    JsonObject changed = favorites();
    changed.addProperty("title", "新的收藏");
    changed.addProperty("coverUrl", "https://example.test/new.png");
    changed.getAsJsonArray("items").remove(1);
    changed.getAsJsonObject("pagination").addProperty("total", 1);
    var refreshed = GogoMusicService.parsePlaylistSnapshot(changed, MusicPlatform.GOGO, "favorites:Test");
    assertEquals(original.getId(), refreshed.getId());
    assertNotEquals(original.getCoverLocation(), refreshed.getCoverLocation());
    assertEquals(2, original.getMusics().size());
    assertEquals(1, refreshed.getMusics().size());
    assertNotSame(original.getMusics(), refreshed.getMusics());

    var loaded = ImportedPlaylistStore.class.getDeclaredField("loaded");
    var playlists = ImportedPlaylistStore.class.getDeclaredField("PLAYLISTS");
    loaded.setAccessible(true);
    playlists.setAccessible(true);
    boolean wasLoaded = loaded.getBoolean(null);
    @SuppressWarnings("unchecked")
    Map<String, com.muoniumplayer.core.ncm.music.dto.PlayList> records =
        (Map<String, com.muoniumplayer.core.ncm.music.dto.PlayList>) playlists.get(null);
    var saved = new LinkedHashMap<>(records);
    try {
      loaded.setBoolean(null, true);
      records.clear();
      assertFalse(ImportedPlaylistStore.replaceIfCurrent(original, refreshed));
      records.put(ImportedPlaylistStore.keyOf(refreshed), refreshed);
      assertFalse(ImportedPlaylistStore.replaceIfCurrent(original, original));
      assertSame(refreshed, records.get(ImportedPlaylistStore.keyOf(original)));
    } finally {
      records.clear();
      records.putAll(saved);
      loaded.setBoolean(null, wasLoaded);
    }
  }

  @Test
  void snapshotHttpUsesOneAnonymousGetAndHidesTokensInErrors() throws Exception {
    var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger calls = new AtomicInteger();
    List<String> credentials = new ArrayList<>();
    server.createContext("/api/shares/favorites/TestToken", exchange -> {
      calls.incrementAndGet();
      credentials.add(exchange.getRequestHeaders().getFirst("Authorization"));
      credentials.add(exchange.getRequestHeaders().getFirst("Cookie"));
      byte[] body = ("{\"success\":true,\"code\":2000,\"data\":" + favorites() + "}").getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.createContext("/api/shares/favorites/InvalidToken", exchange -> {
      exchange.sendResponseHeaders(404, -1);
      exchange.close();
    });
    server.createContext("/api/shares/favorites/BusinessToken", exchange -> {
      byte[] body = "{\"success\":false,\"code\":4004,\"message\":\"BusinessToken\"}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      var client = new GogoApiClient("http://127.0.0.1:" + server.getAddress().getPort());
      var snapshot = GogoMusicService.playlistSnapshot(MusicPlatform.GOGO, "favorites:TestToken", client);
      assertEquals(2, snapshot.getMusics().size());
      snapshot.loadMusicsWithCallback(tracks -> assertEquals(2, tracks.size()));
      assertEquals(1, calls.get());
      assertTrue(credentials.stream().allMatch(Objects::isNull));
      for (String token : List.of("InvalidToken", "BusinessToken")) {
        IOException failure = assertThrows(IOException.class,
            () -> GogoMusicService.playlistSnapshot(MusicPlatform.UNM, "favorites:" + token, client));
        assertEquals("分享链接不存在或已失效", failure.getMessage());
        assertNull(failure.getCause());
      }
    } finally {
      server.stop(0);
    }
  }

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
