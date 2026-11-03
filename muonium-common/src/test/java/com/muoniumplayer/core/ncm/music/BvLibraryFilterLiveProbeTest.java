package com.muoniumplayer.core.ncm.music;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 只读验证全部筛选值和排序参数，不触发音频下载、歌词生成或播放计数。 */
@EnabledIfEnvironmentVariable(named = "MUONIUM_FILTERS_LIVE", matches = "1")
class BvLibraryFilterLiveProbeTest {
  @Test
  void gogoFiltersAndSorts() throws Exception {
    probe(MusicPlatform.GOGO);
  }

  @Test
  void unmFiltersAndSorts() throws Exception {
    probe(MusicPlatform.UNM);
  }

  private void probe(MusicPlatform platform) throws Exception {
    var filters = GogoMusicService.filters(platform);
    int queries = 0;
    for (String key : List.of("languages", "styles")) {
      var values = GogoMusicService.array(filters, key);
      assertFalse(values.isEmpty(), platform + " " + key);
      for (var value : values) {
        String selected = value.getAsString();
        var page =
            GogoMusicService.songs(
                platform,
                "",
                key.equals("languages") ? selected : "",
                key.equals("styles") ? selected : "",
                "createdAt",
                "desc",
                1);
        assertFalse(page.items.isEmpty(), platform + " " + key + "=" + selected);
        assertEquals(1, page.page);
        assertTrue(page.items.stream().allMatch(song -> song.getSource() == platform));
        System.out.println(platform + "_FILTER " + key + "=" + selected + " total=" + page.total);
        queries++;
      }
    }
    for (String sort : List.of("createdAt", "playCount", "favCount", "heatScore")) {
      long asc = 0, desc = 0;
      for (String order : List.of("asc", "desc")) {
        var page = GogoMusicService.songs(platform, "", "", "", sort, order, 1);
        assertFalse(page.items.isEmpty(), platform + " " + sort + " " + order);
        assertEquals(1, page.page);
        if (order.equals("asc")) asc = page.items.get(0).getId();
        else desc = page.items.get(0).getId();
        System.out.println(
            platform + "_SORT " + sort + " " + order + " first=" + page.items.get(0).getId());
        queries++;
      }
      if (sort.equals("createdAt")) assertNotEquals(asc, desc, "时间升降序必须得到不同首曲");
    }
    System.out.println(platform + "_FILTER_SUMMARY queries=" + queries);
  }
}
