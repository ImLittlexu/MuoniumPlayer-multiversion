package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/** GOGO/UNM 的公开 API；凭据与音频 CDN 请求不混用。 */
public final class GogoApiClient {
  public static final GogoApiClient INSTANCE = new GogoApiClient("https://api.ggd.fan", "GOGO娱乐");
  private static final GogoApiClient UNM = new GogoApiClient("https://api.unm.fan", "UNM小站");
  private final String base;
  private final String name;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  GogoApiClient(String base) {
    this(base, "曲库");
  }

  private GogoApiClient(String base, String name) {
    this.base = base;
    this.name = name;
  }

  public static GogoApiClient forPlatform(MusicPlatform platform) {
    if (platform == MusicPlatform.GOGO) return INSTANCE;
    if (platform == MusicPlatform.UNM) return UNM;
    throw new IllegalArgumentException("不支持的 BV/CID 曲库平台：" + platform);
  }

  public JsonObject get(String path, Map<String, String> query) throws IOException {
    String suffix =
        query.entrySet().stream()
            .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
            .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
            .collect(Collectors.joining("&"));
    return request("GET", path + (suffix.isEmpty() ? "" : "?" + suffix));
  }

  public void reportPlay(String id) throws IOException {
    request("POST", "/api/songs/" + id + "/play");
  }

  private JsonObject request(String method, String path) throws IOException {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(base + path))
              .timeout(Duration.ofSeconds(15))
              .header("Accept", "application/json")
              .header("User-Agent", "MuoniumPlayer/BvLibrary")
              .method(method, HttpRequest.BodyPublishers.noBody())
              .build();
      HttpResponse<String> response =
          http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() < 200 || response.statusCode() >= 300)
        throw new IOException(name + " HTTP " + response.statusCode());
      return unwrap(response.body());
    } catch (InterruptedException cancelled) {
      Thread.currentThread().interrupt();
      throw new java.io.InterruptedIOException(name + " request cancelled");
    } catch (IllegalArgumentException | IllegalStateException malformed) {
      throw new IOException("曲库返回数据格式错误", malformed);
    }
  }

  static JsonObject unwrap(String body) throws IOException {
    try {
      JsonObject envelope = JsonParser.parseString(body).getAsJsonObject();
      if (!envelope.has("success")
          || !envelope.get("success").getAsBoolean()
          || !envelope.has("code")
          || envelope.get("code").getAsInt() != 2000)
        throw new IOException("4004".equals(GogoMusicService.text(envelope, "code"))
            ? "业务错误 4004" : GogoMusicService.text(envelope, "message"));
      return envelope.has("data") && envelope.get("data").isJsonObject()
          ? envelope.getAsJsonObject("data")
          : new JsonObject();
    } catch (RuntimeException malformed) {
      throw new IOException("曲库返回数据格式错误", malformed);
    }
  }

  static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
