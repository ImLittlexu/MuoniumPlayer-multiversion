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
    return playlistSnapshot(platform, id).getMusics();
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
    for (JsonObject entry : entries) songs.add(snapshotSong(object(entry, "song"), platform));
    return songs;
  }

  public static final String FAVORITES_PREFIX = "favorites:";

  static String playlistPath(String id) throws IOException {
    if (id != null && id.startsWith(FAVORITES_PREFIX)) {
      String token = id.substring(FAVORITES_PREFIX.length());
      if (!token.matches("[A-Za-z0-9_-]+")) throw new IOException("无效的收藏分享链接");
      return "/api/shares/favorites/" + token;
    }
    return "/api/playlists/" + positiveId(id);
  }

  public static PlayList playlistSnapshot(MusicPlatform platform, String id) throws IOException {
    return playlistSnapshot(platform, id, GogoApiClient.forPlatform(platform));
  }

  static PlayList playlistSnapshot(MusicPlatform platform, String id, GogoApiClient api)
      throws IOException {
    String path = playlistPath(id);
    boolean favorites = id.startsWith(FAVORITES_PREFIX);
    try {
      return parsePlaylistSnapshot(api.get(path, Map.of()), platform, id);
    } catch (IOException failure) {
      // 分享 token 是读取凭证，网络异常和服务端消息均不得把它带入日志。
      String reason = failure.getMessage();
      if (favorites && (reason != null && (reason.contains("404") || reason.contains("4004"))))
        throw new IOException("分享链接不存在或已失效");
      if (failure instanceof java.io.InterruptedIOException) {
        Thread.currentThread().interrupt();
        throw new java.io.InterruptedIOException("歌单加载已取消");
      }
      throw new IOException("歌单加载失败，请检查网络、访问权限或返回数据");
    } catch (RuntimeException malformed) {
      throw new IOException("歌单返回数据格式错误");
    }
  }

  static PlayList parsePlaylistSnapshot(JsonObject data, MusicPlatform platform, String id)
      throws IOException {
    playlistPath(id);
    boolean favorites = id.startsWith(FAVORITES_PREFIX);
    List<Music> tracks;
    try {
      if (favorites) {
        JsonArray items = requiredArray(data, "items");
        if (data.has("pagination")) {
          JsonObject pagination = object(data, "pagination");
          if (number(pagination, "total") != items.size()
              || number(pagination, "totalPages") > 1 || number(pagination, "page") > 1)
            throw new IOException("收藏分享未返回完整曲目");
        }
        tracks = new ArrayList<>();
        for (JsonElement item : items) tracks.add(snapshotSong(item.getAsJsonObject(), platform));
      } else {
        if (!positiveId(text(data, "id")).equals(id)) throw new IOException("歌单 ID 不匹配");
        requiredArray(data, "songs");
        tracks = parsePlaylistSongs(data, platform);
      }
      String title = text(data, "title").trim();
      if (title.isEmpty()) title = platform.getDisplayName() + (favorites ? "收藏" : "歌单");
      String cover = text(data, "coverUrl").replace("http://", "https://");
      if (cover.isEmpty() && !tracks.isEmpty()) cover = tracks.get(0).getExternalCoverUrl();
      long key = favorites
          ? Math.max(1L, PlaylistImportService.stableLong(platform.name() + ":" + id))
          : Long.parseLong(id);
      PlayList result = new PlayList(key, title, cover, tracks.size(), 0L, null, "", 0L);
      result.setPlatform(platform);
      result.setPlatformPlaylistId(id);
      result.musics = new CopyOnWriteArrayList<>(tracks);
      result.musicsQueried = true;
      result.musicsLoaded = true;
      return result;
    } catch (RuntimeException malformed) {
      throw new IOException("歌单返回数据格式错误");
    }
  }

  private static JsonArray requiredArray(JsonObject data, String field) throws IOException {
    if (!data.has(field) || !data.get(field).isJsonArray())
      throw new IOException("歌单缺少曲目列表");
    return data.getAsJsonArray(field);
  }

  private static Music snapshotSong(JsonObject item, MusicPlatform platform) throws IOException {
    if (text(item, "songName").trim().isEmpty()
        || !item.has("originalArtist") || !item.get("originalArtist").isJsonArray())
      throw new IOException("歌曲信息不完整");
    return song(item, platform);
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
    try {
      Long.parseLong(id);
    } catch (NumberFormatException overflow) {
      throw new IOException("曲库 ID 超出支持范围");
    }
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
