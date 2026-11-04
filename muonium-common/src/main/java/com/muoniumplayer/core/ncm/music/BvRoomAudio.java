package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.music.dto.Music;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import javax.sound.sampled.AudioSystem;

/** 双站房间只传公开代理定位，各成员仍使用本机的完整取流链路。 */
public final class BvRoomAudio {
  public static final String PROXY_HOST =
      "bilibili-audio-proxy-zone-3e7t6w3b97d6-1307216294.eo-edgefunctions.com";
  public static final long MAX_DURATION_MS = 30 * 60 * 1000L;

  private BvRoomAudio() {}

  public static final class Locator {
    public final String bvid, cid;

    public Locator(String bvid, String cid) {
      if (bvid == null
          || !bvid.matches("BV[0-9A-Za-z]{10}")
          || cid == null
          || !cid.matches("[1-9][0-9]*")) throw new IllegalArgumentException("无效的 BV/CID");
      this.bvid = bvid;
      this.cid = cid;
    }

    public void apply(Music song) {
      song.setGogoBvid(bvid);
      song.setGogoCid(cid);
    }

    public String url() {
      return "https://" + PROXY_HOST + "/proxy?bvid=" + bvid + "&cid=" + cid;
    }
  }

  public static Locator fromUrl(String url) {
    try {
      URI uri = URI.create(url);
      if (!"https".equals(uri.getScheme())
          || !PROXY_HOST.equals(uri.getHost())
          || uri.getRawUserInfo() != null
          || uri.getPort() != -1
          || !"/proxy".equals(uri.getRawPath())
          || uri.getRawFragment() != null) return null;
      Map<String, String> fields = new HashMap<>();
      for (String part : uri.getRawQuery().split("&", -1)) {
        String[] pair = part.split("=", -1);
        if (pair.length != 2
            || !(pair[0].equals("bvid") || pair[0].equals("cid"))
            || fields.putIfAbsent(pair[0], pair[1]) != null) return null;
      }
      return new Locator(fields.get("bvid"), fields.get("cid"));
    } catch (RuntimeException invalid) {
      return null;
    }
  }

  public static long duration(JsonObject response) throws IOException {
    try {
      if (response.get("code").getAsInt() != 0) throw new IOException("B站解析失败");
      String value = response.getAsJsonObject("data").get("timelength").getAsString();
      if (!value.matches("[1-9][0-9]*")) throw new IOException("B站未提供准确时长");
      return requireDuration(Long.parseLong(value));
    } catch (RuntimeException invalid) {
      throw new IOException("B站未提供准确时长", invalid);
    }
  }

  public static long requireDuration(long duration) throws IOException {
    if (duration <= 0 || duration > MAX_DURATION_MS)
      throw new IOException("房间歌曲时长须大于 0 且不超过 30 分钟");
    return duration;
  }

  /** 只读 WAV 头，不创建播放器，也不触发播放统计。 */
  public static long decodedDuration(File audio) throws IOException {
    File wav = AudioCacheFiles.getDecodedWavFile(audio);
    try (var input = AudioSystem.getAudioInputStream(wav)) {
      double rate = input.getFormat().getFrameRate();
      long frames = input.getFrameLength();
      if (rate <= 0 || frames <= 0) throw new IOException("解码音频没有有效时长");
      return Math.round(frames * 1000.0 / rate);
    } catch (javax.sound.sampled.UnsupportedAudioFileException invalid) {
      throw new IOException("解码音频无效", invalid);
    }
  }

  public static void validateDuration(File audio, Music song) throws IOException {
    long expected = song.getPartyExpectedDurationMs();
    if (expected > 0) checkDuration(expected, decodedDuration(audio));
  }

  public static void checkDuration(long expected, long actual) throws IOException {
    requireDuration(expected);
    if (actual <= 0 || Math.abs(expected - actual) > 1000L)
      throw new IOException("本机音频时长与房间不一致：" + actual + "/" + expected + "ms");
  }
}
