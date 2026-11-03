package com.muoniumplayer.core.ncm.music;

import static org.junit.jupiter.api.Assertions.*;

import com.muoniumplayer.core.ncm.music.dto.Music;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/** 显式启用的只读联网探针：不调用播放计数或其他写接口。 */
@EnabledIfEnvironmentVariable(named = "MUONIUM_GOGO_LIVE", matches = "1")
class GogoLiveProbeTest {
  @TempDir Path directory;

  @Test
  void liveCatalogAndThreeAudioRoutes() throws Exception {
    probe(MusicPlatform.GOGO);
  }

  @Test
  void liveUnmCatalogAndTwoAudioRoutes() throws Exception {
    probe(MusicPlatform.UNM);
  }

  private void probe(MusicPlatform platform) throws Exception {
    GogoMusicService.Page<Music> page =
        GogoMusicService.songs(platform, "", "", "", "createdAt", "desc", 1);
    assertFalse(page.items.isEmpty());
    assertFalse(GogoMusicService.filters(platform).entrySet().isEmpty());
    var playlists = GogoMusicService.playlists(platform, "", "hot", 1);
    if (!playlists.items.isEmpty())
      assertNotNull(
          GogoMusicService.playlistSongs(platform, playlists.items.get(0).getPlatformPlaylistId()));
    Music song = page.items.get(0);
    Map<String, List<String>> routes = new LinkedHashMap<>();
    try {
      routes.put("direct", GogoAudioResolver.directUrls(song));
    } catch (Exception failure) {
      System.out.println(platform.name() + "_LIVE direct resolve failed: " + failure.getMessage());
    }
    if (platform == MusicPlatform.GOGO)
      routes.put(
          "r2",
          List.of("https://cdn.eoes.cc/gogo/music/" + GogoAudioResolver.locatorKey(song) + ".m4a"));
    routes.put(
        "proxy",
        List.of(
            "https://bilibili-audio-proxy-zone-3e7t6w3b97d6-1307216294.eo-edgefunctions.com/proxy?bvid="
                + song.getGogoBvid()
                + "&cid="
                + song.getGogoCid()));
    HttpClient http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    int successes = 0;
    for (var route : routes.entrySet()) {
      long begin = System.nanoTime();
      File audio = directory.resolve(route.getKey() + ".mp4").toFile();
      File decoded = AudioCacheFiles.getDecodedWavFile(audio);
      try {
        Exception last = null;
        for (String url : route.getValue()) {
          try {
            HttpRequest request =
                HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(45))
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Referer", "https://www.bilibili.com/video/" + song.getGogoBvid() + "/")
                    .GET()
                    .build();
            HttpResponse<InputStream> reply =
                http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = reply.body()) {
              if (reply.statusCode() != 200) throw new IOException("HTTP " + reply.statusCode());
              Files.copy(body, audio.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            GogoAudioResolver.validate(audio, song);
            last = null;
            break;
          } catch (Exception failure) {
            last = failure;
          }
        }
        if (last != null) throw last;
        if (!audio.isFile()) throw new IOException("no audio URL");
        successes++;
        System.out.println(
            platform.name()
                + "_LIVE "
                + route.getKey()
                + " OK "
                + (System.nanoTime() - begin) / 1_000_000
                + "ms bytes="
                + audio.length());
      } catch (Exception failure) {
        System.out.println(
            platform.name()
                + "_LIVE "
                + route.getKey()
                + " FAILED "
                + (System.nanoTime() - begin) / 1_000_000
                + "ms "
                + failure.getMessage());
      } finally {
        Files.deleteIfExists(audio.toPath());
        Files.deleteIfExists(decoded.toPath());
      }
    }
    assertTrue(successes > 0, "At least one live route must yield decodable audio");
  }
}
