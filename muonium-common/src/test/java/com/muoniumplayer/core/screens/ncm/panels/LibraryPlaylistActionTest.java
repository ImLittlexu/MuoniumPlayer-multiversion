package com.muoniumplayer.core.screens.ncm.panels;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.muoniumplayer.core.ncm.music.GogoMusicService;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.dto.Music;
import org.junit.jupiter.api.Test;

class LibraryPlaylistActionTest {
  private static Music librarySong(MusicPlatform platform) throws Exception {
    return GogoMusicService.song(
        JsonParser.parseString(
                "{\"id\":481,\"songName\":\"曲库歌曲\",\"originalArtist\":[\"歌手\"],"
                    + "\"bv\":\"BV1ETsX61EyX\",\"cid\":\"43133044682\"}")
            .getAsJsonObject(),
        platform);
  }

  private static boolean canAddToPlaylist(Music music) throws Exception {
    var method = MusicWidget.class.getDeclaredMethod("canAddToPlaylist", Music.class);
    method.setAccessible(true);
    return (boolean) method.invoke(null, music);
  }

  @Test
  void bothLibrariesExposeLocalPlaylistActionWithoutCloudFavourites() throws Exception {
    for (MusicPlatform platform : new MusicPlatform[] {MusicPlatform.GOGO, MusicPlatform.UNM}) {
      Music music = librarySong(platform);
      assertTrue(canAddToPlaylist(music), platform + " must allow adding to local playlists");
      assertFalse(LikeButton.canFavourite(music), "No cloud login means no cloud favourite action");
    }
    assertFalse(canAddToPlaylist(null));
  }

  @Test
  void hiddenCloudHeartCannotPushLibraryActionsOutsideTheRow() throws Exception {
    for (MusicPlatform platform : new MusicPlatform[] {MusicPlatform.GOGO, MusicPlatform.UNM}) {
      boolean likeable = LikeButton.canFavourite(librarySong(platform));
      for (double rowWidth : new double[] {240, 768, 1024}) {
        double anchor = rowWidth - 8 - 22;
        double add = MusicWidget.playlistActionX(anchor, likeable);
        double playNext = add - 4 - 20;
        assertEquals(anchor - 24, add, "The hidden heart must not occupy a slot");
        assertTrue(playNext >= 0, "Play-next stays within the row");
        assertTrue(playNext + 20 < add, "Actions do not overlap");
        assertTrue(add + 20 <= rowWidth, "Add-to-playlist stays within the right edge");
        assertEquals(
            add + 100,
            MusicWidget.playlistActionX(anchor + 100, likeable),
            "Resizing must recompute the button from the current row width");
      }
    }
  }

  @Test
  void platformsWithVisibleCloudHeartKeepTheirExistingActionSlot() {
    assertEquals(222, MusicWidget.playlistActionX(270, true));
    assertEquals(246, MusicWidget.playlistActionX(270, false));
  }
}
