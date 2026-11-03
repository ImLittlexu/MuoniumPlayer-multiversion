package com.muoniumplayer.core.screens.ncm.panels;

import com.google.gson.JsonParser;
import com.muoniumplayer.core.ncm.music.GogoMusicService;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.dto.Music;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LibraryPlaylistActionTest {
    private static boolean canAddToPlaylist(Music music) throws Exception {
        var method = MusicWidget.class.getDeclaredMethod("canAddToPlaylist", Music.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, music);
    }
    @Test void bothLibrariesExposeLocalPlaylistActionWithoutCloudFavourites() throws Exception {
        for (MusicPlatform platform : new MusicPlatform[]{MusicPlatform.GOGO, MusicPlatform.UNM}) {
            Music music = GogoMusicService.song(JsonParser.parseString(
                "{\"id\":481,\"songName\":\"曲库歌曲\",\"originalArtist\":[\"歌手\"],"
                + "\"bv\":\"BV1ETsX61EyX\",\"cid\":\"43133044682\"}").getAsJsonObject(), platform);
            assertTrue(canAddToPlaylist(music), platform + " must allow adding to local playlists");
            assertFalse(LikeButton.canFavourite(music), "No cloud login means no cloud favourite action");
        }
        assertFalse(canAddToPlaylist(null));
    }
}
