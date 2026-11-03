package com.muoniumplayer.core.ncm.music;

import com.google.gson.*;
import com.muoniumplayer.core.ncm.music.dto.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public final class GogoMusicService {
  private GogoMusicService() {}

  public static final class Page<T> {
    public final List<T> items;
    public final int page, totalPages, total;

    Page(List<T> items, JsonObject pagination) {
      this.items = List.copyOf(items);
      page = Math.max(1, number(pagination, "page"));
      totalPages = Math.max(1, number(pagination, "totalPages"));
      total = Math.max(0, number(pagination, "total"));
    }
  }

  public static Page<Music> songs(
      String keyword, String language, String style, String sortBy, String sortOrder, int page)
      throws IOException {
    return songs(MusicPlatform.GOGO, keyword, language, style, sortBy, sortOrder, page);
  }

  public static Page<Music> songs(
      MusicPlatform platform,
      String keyword,
      String language,
      String style,
      String sortBy,
      String sortOrder,
      int page)
      throws IOException {
    JsonObject data =
        GogoApiClient.forPlatform(platform)
            .get(
                "/api/songs",
                Map.of(
                    "keyword",
                    keyword,
                    "language",
                    language,
                    "style",
                    style,
                    "sortBy",
                    sortBy,
                    "sortOrder",
                    sortOrder,
                    "page",
                    String.valueOf(page),
                    "pageSize",
                    "20"));
    return parseSongs(data, platform);
  }

  static Page<Music> parseSongs(JsonObject data) throws IOException {
    return parseSongs(data, MusicPlatform.GOGO);
  }

  static Page<Music> parseSongs(JsonObject data, MusicPlatform platform) throws IOException {
    List<Music> songs = new ArrayList<>();
    for (JsonElement item : array(data, "items")) songs.add(song(item.getAsJsonObject(), platform));
    return new Page<>(songs, object(data, "pagination"));
  }

  public static JsonObject filters() throws IOException {
    return filters(MusicPlatform.GOGO);
  }

  public static JsonObject filters(MusicPlatform platform) throws IOException {
    return GogoApiClient.forPlatform(platform).get("/api/songs/filters", Map.of());
  }

  public static Page<PlayList> playlists(String keyword, String sort, int page) throws IOException {
    return playlists(MusicPlatform.GOGO, keyword, sort, page);
  }

  public static Page<PlayList> playlists(
      MusicPlatform platform, String keyword, String sort, int page) throws IOException {
    JsonObject data =
        GogoApiClient.forPlatform(platform)
            .get(
                "/api/playlists/discover",
                Map.of(
                    "keyword",
                    keyword,
                    "sortBy",
                    sort,
                    "page",
                    String.valueOf(page),
                    "pageSize",
                    "20"));
    List<PlayList> lists = new ArrayList<>();
    for (JsonElement item : array(data, "items"))
      lists.add(playlist(item.getAsJsonObject(), platform));
    return new Page<>(lists, object(data, "pagination"));
  }

  public static List<Music> playlistSongs(String id) throws IOException {
    return playlistSongs(MusicPlatform.GOGO, id);
  }

  public static List<Music> playlistSongs(MusicPlatform platform, String id) throws IOException {
    JsonObject data =
        GogoApiClient.forPlatform(platform).get("/api/playlists/" + positiveId(id), Map.of());
    return parsePlaylistSongs(data, platform);
  }

  static List<Music> parsePlaylistSongs(JsonObject data) throws IOException {
    return parsePlaylistSongs(data, MusicPlatform.GOGO);
  }

  static List<Music> parsePlaylistSongs(JsonObject data, MusicPlatform platform)
      throws IOException {
    List<JsonObject> entries = new ArrayList<>();
    for (JsonElement item : array(data, "songs")) entries.add(item.getAsJsonObject());
    entries.sort(Comparator.comparingInt(item -> number(item, "sortOrder")));
    List<Music> songs = new ArrayList<>();
    for (JsonObject entry : entries) songs.add(song(object(entry, "song"), platform));
    return songs;
  }

  public static void ensureLocator(Music song) throws IOException {
    if (validLocator(song.getGogoBvid(), song.getGogoCid())) return;
    JsonObject data =
        GogoApiClient.forPlatform(song.getSource())
            .get("/api/songs/" + positiveId(song.getSourceId()), Map.of());
    setLocator(song, text(data, "bv"), text(data, "cid"));
  }

  public static void setLocator(Music song, String bv, String cid) throws IOException {
    if (!validLocator(bv, cid)) throw new IOException("曲库歌曲缺少有效的 BV / CID");
    song.setGogoBvid(bv);
    song.setGogoCid(cid);
  }

  static boolean validLocator(String bv, String cid) {
    return bv != null
        && bv.matches("BV[0-9A-Za-z]{10}")
        && cid != null
        && cid.matches("[1-9][0-9]*");
  }

  static String positiveId(String id) throws IOException {
    if (id == null || !id.matches("[1-9][0-9]*")) throw new IOException("无效的曲库歌曲或歌单 ID");
    return id;
  }

  public static Music song(JsonObject item) throws IOException {
    return song(item, MusicPlatform.GOGO);
  }

  public static Music song(JsonObject item, MusicPlatform platform) throws IOException {
    GogoApiClient.forPlatform(platform);
    String id = positiveId(text(item, "id"));
    List<Artist> artists = new ArrayList<>();
    for (JsonElement artist : array(item, "originalArtist"))
      artists.add(new Artist(0L, artist.getAsString(), List.of(), List.of()));
    String cover = text(item, "coverUrl").replace("http://", "https://");
    Music music =
        new Music(
            text(item, "songName"),
            text(item, "songName"),
            "",
            Long.parseLong(id),
            artists,
            List.of(),
            new Album(0L, "", cover, List.of()),
            0L,
            0L,
            0L,
            List.of());
    music.setSource(platform);
    music.setSourceId(id);
    music.setExternalCoverUrl(cover);
    music.setForeignCover(!cover.isEmpty());
    setLocator(music, text(item, "bv"), text(item, "cid"));
    return music;
  }

  static PlayList playlist(JsonObject data) throws IOException {
    return playlist(data, MusicPlatform.GOGO);
  }

  static PlayList playlist(JsonObject data, MusicPlatform platform) throws IOException {
    String id = positiveId(text(data, "id"));
    PlayList list =
        new PlayList(
            Long.parseLong(id),
            text(data, "title"),
            text(data, "coverUrl").replace("http://", "https://"),
            number(data, "songCount"),
            0L,
            null,
            "",
            0L);
    list.setPlatform(platform);
    list.setPlatformPlaylistId(id);
    return list;
  }

  public static PlayList songList(List<Music> songs, String title) {
    return songList(songs, title, MusicPlatform.GOGO);
  }

  public static PlayList songList(List<Music> songs, String title, MusicPlatform platform) {
    PlayList list = new PlayList(0L, title, "", songs.size(), 0L, null, "", 0L);
    list.setPlatform(platform);
    list.setSearchMode(true);
    list.setMusics(new CopyOnWriteArrayList<>(songs));
    list.setMusicsQueried(true);
    list.setMusicsLoaded(true);
    return list;
  }

  public static String text(JsonObject object, String key) {
    JsonElement value = object.get(key);
    return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
  }

  static int number(JsonObject o, String key) {
    String value = text(o, key);
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException bad) {
      return 0;
    }
  }

  static JsonObject object(JsonObject o, String key) throws IOException {
    if (!o.has(key) || !o.get(key).isJsonObject()) throw new IOException("曲库数据缺少 " + key);
    return o.getAsJsonObject(key);
  }

  public static JsonArray array(JsonObject o, String key) {
    return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
  }
}
