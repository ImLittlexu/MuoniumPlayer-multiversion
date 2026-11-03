package com.muoniumplayer.core.ncm.music;

import static org.junit.jupiter.api.Assertions.*;

import com.muoniumplayer.core.screens.ncm.LyricParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 只调用公开 GET；输出行数和来源，不打印歌词正文。 */
@EnabledIfEnvironmentVariable(named = "MUONIUM_LYRICS_LIVE", matches = "1")
class BvLibraryLyricLiveProbeTest {
  @Test
  void gogoPublicLyrics() throws Exception {
    probe(MusicPlatform.GOGO, 481, 480, 479);
  }

  @Test
  void unmPublicLyrics() throws Exception {
    probe(MusicPlatform.UNM, 710, 708, 709);
  }

  private void probe(MusicPlatform platform, int... ids) throws Exception {
    int available = 0;
    for (int id : ids) {
      var record = GogoMusicTest.record("43133044682");
      record.addProperty("id", id);
      var music = GogoMusicService.song(record, platform);
      var result = BvLibraryLyricService.fetch(music);
      assertFalse(LyricParser.hasRealWordTiming(result.lines));
      long last = -1;
      for (var line : result.lines) {
        assertTrue(line.timestamp >= 0 && line.timestamp >= last);
        assertTrue(line.duration >= 0);
        assertFalse(line.lyric.isBlank());
        assertTrue(line.words.isEmpty());
        last = line.timestamp;
      }
      if (!result.lines.isEmpty()) available++;
      System.out.println(
          platform
              + "_LYRICS id="
              + id
              + " lines="
              + result.lines.size()
              + " ai="
              + (result.rawJson.has(BvLibraryLyricService.METADATA)
                  && BvLibraryLyricService.isAi(
                      result.rawJson.getAsJsonObject(BvLibraryLyricService.METADATA))));
    }
    assertTrue(available > 0, "至少一首样本能解析出平台歌词");
  }
}
