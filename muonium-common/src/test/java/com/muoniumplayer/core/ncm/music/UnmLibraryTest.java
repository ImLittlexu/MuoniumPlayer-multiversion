package com.muoniumplayer.core.ncm.music;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.muoniumplayer.core.ncm.music.dto.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnmLibraryTest {
  @TempDir Path temp;

  static Music song() throws IOException {
    return GogoMusicService.song(GogoMusicTest.record("42828759367"), MusicPlatform.UNM);
  }

  @Test
  void sameNumericIdCannotCollideWithGogoOrLoseItsPlatformOnRestore() throws Exception {
    Music unm = song(), gogo = GogoMusicService.song(GogoMusicTest.record("42828759367"));
    assertNotEquals(gogo.getStableKey(), unm.getStableKey());
    assertNotEquals(gogo, unm);
    Music restored = LocalPlaylistStore.deserializeTrack(LocalPlaylistStore.serializeTrack(unm));
    assertEquals(MusicPlatform.UNM, restored.getSource());
    assertEquals(unm.getStableKey(), restored.getStableKey());
    assertEquals(unm.getGogoCid(), restored.getGogoCid());
    assertEquals(unm.getGogoBvid(), unm.queueIdentityCopy().getGogoBvid());
    assertEquals(MusicPlatform.UNM, unm.queueIdentityCopy().getSource());
    assertTrue(unm.isBvLibrary());
    assertFalse(unm.isGogo());
    assertTrue(unm.getPlayUrl().getA().startsWith("muonium-unm://song/"));
    assertFalse(CrossSourceSwitcher.canAutoSwitch(unm));
    assertNull(NeteaseMusicService.getLyric(unm));
  }

  @Test
  void catalogAndPlaylistMappingPropagateTheRequestedPlatform() throws Exception {
    JsonObject data =
        GogoMusicTest.json(
            "{\"items\":[],\"pagination\":{\"page\":1,\"totalPages\":1,\"total\":1}}");
    data.getAsJsonArray("items").add(GogoMusicTest.record("42828759367"));
    assertEquals(
        MusicPlatform.UNM,
        GogoMusicService.parseSongs(data, MusicPlatform.UNM).items.get(0).getSource());
    JsonObject detail = GogoMusicTest.json("{\"songs\":[]}");
    JsonObject entry = new JsonObject();
    entry.addProperty("sortOrder", 1);
    entry.add("song", GogoMusicTest.record("42828759367"));
    detail.getAsJsonArray("songs").add(entry);
    assertEquals(
        MusicPlatform.UNM,
        GogoMusicService.parsePlaylistSongs(detail, MusicPlatform.UNM).get(0).getSource());
    PlayList playlist =
        GogoMusicService.playlist(
            GogoMusicTest.json("{\"id\":28,\"title\":\"公开歌单\",\"songCount\":3}"),
            MusicPlatform.UNM);
    assertEquals(MusicPlatform.UNM, playlist.getPlatform());
    assertEquals("28", playlist.getPlatformPlaylistId());
    assertEquals(
        MusicPlatform.UNM,
        GogoMusicService.songList(List.of(song()), "曲库", MusicPlatform.UNM).getPlatform());
    assertNotSame(
        GogoApiClient.forPlatform(MusicPlatform.GOGO),
        GogoApiClient.forPlatform(MusicPlatform.UNM));
    assertThrows(
        IllegalArgumentException.class, () -> GogoApiClient.forPlatform(MusicPlatform.NETEASE));
  }

  @Test
  void directFailureFallsStraightToProxyAndNeverVisitsR2() throws Exception {
    List<String> urls = new ArrayList<>();
    Music song = song();
    File result =
        GogoAudioResolver.prepare(
            song,
            temp.toFile(),
            "unm",
            s -> List.of("https://direct/audio"),
            (url, file, s) -> {
              urls.add(url);
              if (url.contains("direct")) throw new IOException("403");
              writeWav(file);
            },
            (f, s) -> {});
    assertTrue(result.isFile());
    assertEquals(2, urls.size());
    assertTrue(urls.get(1).contains("/proxy?bvid="));
    assertTrue(urls.stream().noneMatch(url -> url.contains("cdn.eoes.cc")));
    assertEquals("代理", song.getGogoAudioRoute());
  }

  @Test
  void directApiFailureLeavesOnlyOneProxyAttemptAndAnAccurateError() throws Exception {
    List<String> urls = new ArrayList<>();
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                GogoAudioResolver.prepare(
                    song(),
                    temp.toFile(),
                    "failed",
                    s -> {
                      throw new IOException("api failed");
                    },
                    (url, file, s) -> {
                      urls.add(url);
                      throw new IOException("proxy failed");
                    },
                    (f, s) -> {}));
    assertEquals(1, urls.size());
    assertTrue(urls.get(0).contains("/proxy?"));
    assertTrue(error.getMessage().contains("UNM小站"));
    assertFalse(error.getMessage().contains("R2"));
    assertFalse(Files.exists(temp.resolve("failed.candidate.mp4")));
  }

  private static void writeWav(File file) throws IOException {
    byte[] bytes = new byte[48];
    System.arraycopy(new byte[] {82, 73, 70, 70}, 0, bytes, 0, 4);
    System.arraycopy(new byte[] {87, 65, 86, 69}, 0, bytes, 8, 4);
    Files.write(file.toPath(), bytes);
  }
}
