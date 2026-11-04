package com.muoniumplayer.core.ncm.music;

import com.google.gson.*;
import com.muoniumplayer.core.ncm.music.dto.Music;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** 三条线路只改变音频来源，不改变曲目身份。 */
public final class GogoAudioResolver {
  public static final String PREFIX = "muonium-gogo://song/";
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  private GogoAudioResolver() {}

  public static String prefix(MusicPlatform platform) {
    return platform == MusicPlatform.UNM ? "muonium-unm://song/" : PREFIX;
  }

  interface Direct {
    List<String> urls(Music song) throws Exception;
  }

  interface Transfer {
    void download(String url, File target, Music song) throws Exception;
  }

  interface Validator {
    void validate(File file, Music song) throws Exception;
  }

  static String locatorKey(Music song) {
    return song.getGogoBvid() + "_" + song.getGogoCid();
  }

  static List<String> directUrls(Music song) throws Exception {
    return parseDirectUrls(playData(song));
  }

  public static long roomDuration(Music song) throws Exception {
    return BvRoomAudio.duration(playData(song));
  }

  private static JsonObject playData(Music song) throws Exception {
    String url =
        "https://api.bilibili.com/x/player/playurl?bvid="
            + song.getGogoBvid()
            + "&cid="
            + song.getGogoCid()
            + "&qn=32&fnval=16&fnver=0&fourk=0&try_look=1&gaia_source=view-card&otype=json";
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", "Mozilla/5.0")
            .header("Referer", "https://www.bilibili.com/video/" + song.getGogoBvid() + "/")
            .header("Accept", "application/json")
            .GET()
            .build();
    HttpResponse<String> reply = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    if (reply.statusCode() != 200) throw new IOException("B站解析 HTTP " + reply.statusCode());
    return JsonParser.parseString(reply.body()).getAsJsonObject();
  }

  static List<String> parseDirectUrls(JsonObject root) throws IOException {
    if (!root.has("code") || root.get("code").getAsInt() != 0) throw new IOException("B站解析失败");
    JsonObject dash = GogoMusicService.object(GogoMusicService.object(root, "data"), "dash");
    List<JsonObject> tracks = new ArrayList<>();
    for (JsonElement entry : GogoMusicService.array(dash, "audio")) {
      JsonObject track = entry.getAsJsonObject();
      String codec = GogoMusicService.text(track, "codecs");
      if ("mp4a.40.2".equals(codec)) tracks.add(track);
    }
    tracks.sort(
        Comparator.<JsonObject>comparingInt(t -> GogoMusicService.number(t, "id") == 30280 ? 1 : 0)
            .thenComparingInt(t -> GogoMusicService.number(t, "bandwidth"))
            .reversed());
    // 档位选择一次：同一音轨的备用 CDN 耗尽后再转 R2。
    if (tracks.isEmpty()) throw new IOException("B站没有可用的 AAC-LC 音轨");
    JsonObject track = tracks.get(0);
    LinkedHashSet<String> urls = new LinkedHashSet<>();
    String base = GogoMusicService.text(track, "baseUrl");
    if (base.isEmpty()) base = GogoMusicService.text(track, "base_url");
    addUrl(urls, base);
    String key = track.has("backupUrl") ? "backupUrl" : "backup_url";
    for (JsonElement backup : GogoMusicService.array(track, key))
      addUrl(urls, backup.getAsString());
    return new ArrayList<>(urls);
  }

  private static void addUrl(Set<String> urls, String url) {
    try {
      URI uri = URI.create(url);
      if (("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
          && uri.getHost() != null) urls.add(url);
    } catch (IllegalArgumentException ignored) {
    }
  }

  static File ensureAudio(Music song, File directory, String key) {
    return prepare(
        song,
        directory,
        key,
        GogoAudioResolver::directUrls,
        (url, file, track) -> MusicDownloadService.downloadMusic(url, file, null, track),
        GogoAudioResolver::validate);
  }

  static File prepare(
      Music song,
      File directory,
      String key,
      Direct direct,
      Transfer transfer,
      Validator validator) {
    List<String> urls = new ArrayList<>();
    try {
      checkCancelled(song);
      urls.addAll(direct.urls(song));
    } catch (InterruptedException cancelled) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("曲库音频获取已取消", cancelled);
    } catch (Exception failure) {
      checkCancelled(song);
      System.err.println(
          "[" + song.getSource().getDisplayName() + "] B站解析失败：" + failure.getMessage());
    }
    int directCount = urls.size();
    boolean hasR2 = song.getSource() == MusicPlatform.GOGO;
    if (hasR2) urls.add("https://cdn.eoes.cc/gogo/music/" + locatorKey(song) + ".m4a");
    urls.add(new BvRoomAudio.Locator(song.getGogoBvid(), song.getGogoCid()).url());
    File temporary = new File(directory, key + ".candidate.mp4");
    File decoded = AudioCacheFiles.getDecodedWavFile(temporary);
    IllegalStateException exhausted =
        new IllegalStateException(
            song.getSource().getDisplayName() + (hasR2 ? "：直连、R2 和代理均无法播放" : "：直连和代理均无法播放"));
    for (int i = 0; i < urls.size(); i++) {
      checkCancelled(song);
      String route = i < directCount ? "B站直连" : hasR2 && i == directCount ? "R2" : "代理";
      long started = System.nanoTime();
      try {
        Files.deleteIfExists(temporary.toPath());
        Files.deleteIfExists(decoded.toPath());
        transfer.download(urls.get(i), temporary, song);
        checkCancelled(song);
        if (!temporary.isFile() || temporary.length() < 4) throw new IOException("音频下载失败或为空");
        validator.validate(temporary, song);
        checkCancelled(song);
        String container = AudioContainerSupport.detectContainer(temporary);
        File result = new File(directory, key + "." + container);
        AudioCacheFiles.moveCacheFile(temporary, result);
        if (decoded.isFile())
          AudioCacheFiles.moveCacheFile(decoded, AudioCacheFiles.getDecodedWavFile(result));
        song.setGogoAudioRoute(route);
        System.out.println(
            "["
                + song.getSource().getDisplayName()
                + "] "
                + route
                + " 准备完成 "
                + (System.nanoTime() - started) / 1_000_000
                + "ms");
        return result;
      } catch (Exception failure) {
        checkCancelled(song);
        exhausted.addSuppressed(failure);
        System.err.println(
            "[" + song.getSource().getDisplayName() + "] " + route + " 失败：" + failure.getMessage());
      } finally {
        temporary.delete();
        decoded.delete();
      }
    }
    throw exhausted;
  }

  static void validate(File file, Music song) throws IOException {
    String container = AudioContainerSupport.detectContainer(file);
    if (!AudioContainerSupport.requiresAacDecode(container))
      throw new IOException("音源未返回 AAC/MP4 音频");
    File decoded = AudioCacheFiles.getDecodedWavFile(file);
    Files.createDirectories(decoded.getParentFile().toPath());
    AacAudioDecoder.decodeToWav(file, container, decoded, null);
    if (!AudioCacheFiles.isReusableDecodedWav(file, decoded)) throw new IOException("音频解码失败");
    BvRoomAudio.validateDuration(file, song);
  }

  public static long decodedDuration(File file, Music song) throws IOException {
    if (!AudioCacheFiles.isReusableDecodedWav(file, AudioCacheFiles.getDecodedWavFile(file)))
      validate(file, song);
    long actual = BvRoomAudio.decodedDuration(file);
    if (song.getPartyExpectedDurationMs() > 0)
      BvRoomAudio.checkDuration(song.getPartyExpectedDurationMs(), actual);
    return actual;
  }

  private static void checkCancelled(Music song) {
    if (Thread.currentThread().isInterrupted() || song.roomAudioCancelled())
      throw new IllegalStateException("曲库音频获取已取消");
  }
}
