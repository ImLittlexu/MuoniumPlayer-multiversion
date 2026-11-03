package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** GOGO/UNM 按平台歌曲 ID 精确取回的逐行歌词，不参与跨平台标题匹配。 */
final class BvLibraryLyricService {
  static final String METADATA = "bvLibraryLyrics";
  static final long CACHE_MAX_AGE_MS = 60 * 60 * 1000L;

  private BvLibraryLyricService() {}

  static LyricLoadService.LyricLoadResult fetch(Music music) throws IOException {
    return fetch(music, GogoApiClient.forPlatform(music.getSource()));
  }

  static LyricLoadService.LyricLoadResult fetch(Music music, GogoApiClient api) throws IOException {
    String id = GogoMusicService.positiveId(music.getSourceId());
    return parse(api.get("/api/songs/" + id + "/lyrics", Map.of()), id, music.getDuration());
  }

  static LyricLoadService.LyricLoadResult parse(JsonObject data, String songId, long duration)
      throws IOException {
    try {
      if (!data.has("available")
          || !data.get("available").isJsonPrimitive()
          || !data.getAsJsonPrimitive("available").isBoolean())
        throw new IOException("歌词接口缺少 available 状态");
      if (!songId.equals(GogoMusicService.text(data, "songId")))
        throw new IOException("歌词歌曲 ID 不匹配");
      if (!data.get("available").getAsBoolean()) return LyricLoadService.LyricLoadResult.empty();
      if (!"LRC".equals(GogoMusicService.text(data, "format"))
          || !"LINE".equals(GogoMusicService.text(data, "syncMode")))
        throw new IOException("不支持的曲库歌词格式");
      if (!data.has("lines") || !data.get("lines").isJsonArray())
        throw new IOException("歌词接口缺少时间轴");

      List<LyricLine> lines = new ArrayList<>();
      for (JsonElement element : data.getAsJsonArray("lines")) {
        JsonObject item = element.getAsJsonObject();
        String text = GogoMusicService.text(item, "text").trim();
        if (text.isEmpty()) continue;
        long start = milliseconds(item.get("startMs"));
        LyricLine line = new LyricLine(start, text);
        line.duration = -1L;
        if (item.has("endMs") && !item.get("endMs").isJsonNull()) {
          long end = milliseconds(item.get("endMs"));
          if (end < start) throw new IOException("歌词结束时间早于开始时间");
          line.duration = end - start;
        }
        lines.add(line);
      }
      if (lines.isEmpty()) return LyricLoadService.LyricLoadResult.empty();
      lines.sort(Comparator.comparingLong(line -> line.timestamp));
      boolean openEnd = lines.get(lines.size() - 1).duration < 0L;
      for (int i = 0; i < lines.size(); i++) {
        LyricLine line = lines.get(i);
        if (line.duration >= 0L) continue;
        long end = i + 1 < lines.size() ? lines.get(i + 1).timestamp : duration;
        line.duration = Math.max(0L, end - line.timestamp);
      }

      JsonObject metadata = new JsonObject();
      for (String key :
          List.of("sourceType", "qualityStatus", "disclaimer", "lyricId", "versionId")) {
        if (data.has(key) && !data.get(key).isJsonNull())
          metadata.add(key, data.get(key).deepCopy());
      }
      metadata.addProperty("openEnd", openEnd);
      validateMetadata(metadata);
      JsonObject raw = new JsonObject();
      raw.add(METADATA, metadata);
      return new LyricLoadService.LyricLoadResult(raw, lines);
    } catch (RuntimeException malformed) {
      throw new IOException("曲库歌词时间轴格式错误", malformed);
    }
  }

  private static long milliseconds(JsonElement value) throws IOException {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
      throw new IOException("无效的歌词时间戳");
    long time = value.getAsBigDecimal().longValueExact();
    if (time < 0L) throw new IOException("歌词时间戳不能为负数");
    return time;
  }

  static void validateMetadata(JsonObject metadata) throws IOException {
    for (String key : List.of("sourceType", "qualityStatus", "disclaimer")) {
      JsonElement value = metadata.get(key);
      if (value != null
          && !value.isJsonNull()
          && (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()))
        throw new IOException("歌词来源信息格式错误");
    }
    JsonElement openEnd = metadata.get("openEnd");
    if (openEnd != null
        && (!openEnd.isJsonPrimitive() || !openEnd.getAsJsonPrimitive().isBoolean()))
      throw new IOException("歌词末行标记格式错误");
  }

  static boolean isAi(JsonObject metadata) {
    return metadata != null
        && ("BILIBILI_AI_SUBTITLE".equals(GogoMusicService.text(metadata, "sourceType"))
            || "AI_GENERATED".equals(GogoMusicService.text(metadata, "qualityStatus")));
  }

  /** 目录可能没有时长，主线程提交时再用实际音频时长补齐最后一行。 */
  static void completeLastLine(JsonObject raw, List<LyricLine> lines, long duration) {
    if (raw == null || !raw.has(METADATA) || lines == null || lines.isEmpty()) return;
    JsonObject metadata = raw.getAsJsonObject(METADATA);
    if (!metadata.has("openEnd") || !metadata.get("openEnd").getAsBoolean()) return;
    LyricLine last = lines.get(lines.size() - 1);
    if (duration > last.timestamp) last.duration = duration - last.timestamp;
  }
}
