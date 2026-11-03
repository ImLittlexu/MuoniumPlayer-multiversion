package com.muoniumplayer.core.screens.ncm.panels;

import com.google.gson.*;
import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.*;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.rendering.texture.Textures;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.*;
import com.muoniumplayer.core.screens.ncm.NCMPanel;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import java.util.*;
import java.util.function.*;

/** 两个公开目录；仅发布仍属于当前页面、当前查询的网络结果。 */
public final class GogoLibraryPanel extends NCMPanel {
  private final MusicPlatform platform;
  private boolean playlists, loading, failed;
  private int page = 1, totalPages = 1, revision;
  private String keyword = "",
      language = "",
      style = "",
      sort = "createdAt",
      order = "desc",
      listSort = "hot",
      status = "";
  private JsonObject filters;
  private List<Music> songs = List.of();
  private List<PlayList> lists = List.of();
  private TextFieldWidget search;
  private ScrollPanel content;

  public GogoLibraryPanel(MusicPlatform platform) {
    if (!platform.isBvLibrary()) throw new IllegalArgumentException("不是 BV/CID 曲库平台");
    this.platform = platform;
  }

  @Override
  public void onInit() {
    load();
  }

  private void load() {
    final int expected = ++revision, requestedPage = page;
    final boolean wantLists = playlists;
    final String q = keyword,
        lang = language,
        genre = style,
        s = sort,
        direction = order,
        ls = listSort;
    loading = true;
    failed = false;
    status = "正在加载…";
    layout();
    MultiThreadingUtil.runAsync(
        () -> {
          try {
            JsonObject loadedFilters =
                filters == null ? GogoMusicService.filters(platform) : filters;
            GogoMusicService.Page<?> result =
                wantLists
                    ? GogoMusicService.playlists(platform, q, ls, requestedPage)
                    : GogoMusicService.songs(platform, q, lang, genre, s, direction, requestedPage);
            MultiThreadingUtil.runOnMainThread(
                () -> {
                  if (!current(expected)) return;
                  filters = loadedFilters;
                  page = result.page;
                  totalPages = result.totalPages;
                  if (wantLists) {
                    lists = new ArrayList<>();
                    for (Object item : result.items) lists.add((PlayList) item);
                  } else {
                    songs = new ArrayList<>();
                    for (Object item : result.items) songs.add((Music) item);
                  }
                  loading = false;
                  status = result.items.isEmpty() ? "暂无内容" : "";
                  layout();
                });
          } catch (Exception error) {
            MultiThreadingUtil.runOnMainThread(
                () -> {
                  if (!current(expected)) return;
                  loading = false;
                  failed = true;
                  status = "加载失败：" + error.getMessage();
                  layout();
                });
          }
        });
  }

  private boolean current(int expected) {
    return expected == revision
        && NeteaseMusicService.getCurrentPlatform() == platform
        && NCMScreen.getInstance().getCurrentPanel() == this;
  }

  private void firstPage() {
    page = 1;
    load();
  }

  private void submit() {
    keyword = search.getText().trim();
    firstPage();
  }

  @Override
  public boolean onKeyTypedReceived(char character, int key) {
    if (search != null && search.isFocused() && key == Keyboard.KEY_RETURN) {
      submit();
      return true;
    }
    return super.onKeyTypedReceived(character, key);
  }

  private void layout() {
    getChildren().clear();
    button(
        this,
        () -> "曲库",
        () -> 12,
        () -> 10,
        () -> 86,
        () -> {
          if (playlists) {
            playlists = false;
            keyword = "";
            firstPage();
          }
        },
        () -> !playlists);
    button(
        this,
        () -> "公开歌单",
        () -> 104,
        () -> 10,
        () -> 100,
        () -> {
          if (!playlists) {
            playlists = true;
            keyword = "";
            firstPage();
          }
        },
        () -> playlists);
    search = new TextFieldWidget(FontManager.pf14);
    addChild(search);
    search.setText(keyword).setPlaceholder(playlists ? "搜索歌单 / 创建者" : "搜索歌名 / 原唱");
    search.setBeforeRenderCallback(
        () -> search.setBounds(12, 44, Math.max(60, getWidth() - 112), 26));
    button(this, () -> "搜索", () -> getWidth() - 90, () -> 44, () -> 78, this::submit, () -> false);
    if (playlists) {
      control(
          0,
          1,
          () -> listSortLabel(listSort),
          () ->
              choose(
                  List.of("热门歌单", "最新歌单", "最多收藏", "最多歌曲"),
                  List.of("hot", "new", "favorite", "songCount"),
                  v -> {
                    listSort = v;
                    firstPage();
                  }));
    } else {
      control(
          0,
          4,
          () -> language.isEmpty() ? "全部语种" : language,
          () ->
              filter(
                  "languages",
                  "全部语种",
                  v -> {
                    language = v;
                    firstPage();
                  }));
      control(
          1,
          4,
          () -> style.isEmpty() ? "全部风格" : style,
          () ->
              filter(
                  "styles",
                  "全部风格",
                  v -> {
                    style = v;
                    firstPage();
                  }));
      control(
          2,
          4,
          () -> songSortLabel(sort),
          () ->
              choose(
                  List.of("最新", "播放量", "收藏量", "热度"),
                  List.of("createdAt", "playCount", "favCount", "heatScore"),
                  v -> {
                    sort = v;
                    firstPage();
                  }));
      control(
          3,
          4,
          () -> "desc".equals(order) ? "降序" : "升序",
          () -> {
            order = "desc".equals(order) ? "asc" : "desc";
            firstPage();
          });
      button(
          this,
          () -> "播放本页",
          () -> 12,
          () -> 112,
          () -> 86,
          () -> {
            if (!loading && !failed && !songs.isEmpty()) {
              CloudMusic.currentPlaylistContext = null;
              CloudMusic.play(new ArrayList<>(songs), 0);
            }
          },
          () -> false);
    }
    button(
        this,
        () -> "上一页",
        () -> getWidth() - 220,
        () -> 112,
        () -> 64,
        () -> {
          if (!loading && page > 1) {
            page--;
            load();
          }
        },
        () -> false);
    label(this, () -> page + " / " + totalPages, () -> getWidth() - 148, () -> 119, () -> 65);
    button(
        this,
        () -> "下一页",
        () -> getWidth() - 76,
        () -> 112,
        () -> 64,
        () -> {
          if (!loading && page < totalPages) {
            page++;
            load();
          }
        },
        () -> false);
    content = new ScrollPanel();
    addChild(content);
    content.setSpacing(4).setScrollStrength(40).setAlignment(ScrollPanel.Alignment.VERTICAL);
    content.setBeforeRenderCallback(
        () ->
            content.setBounds(
                12, 148, Math.max(1, getWidth() - 24), Math.max(1, getHeight() - 160)));
    if (loading) {
      PanelLoadingWidget spinner = new PanelLoadingWidget("正在加载…");
      content.addChild(spinner);
      spinner.setBeforeRenderCallback(() -> spinner.setBounds(content.getWidth(), 80));
    } else if (!status.isEmpty()) {
      message(status);
      if (failed)
        button(content, () -> "重试", () -> 0, () -> 0, content::getWidth, this::load, () -> false);
    } else if (playlists) {
      for (PlayList list : lists) playlistRow(list);
    } else {
      PlayList list =
          GogoMusicService.songList(songs, platform.getDisplayName() + " · 曲库", platform);
      for (int i = 0; i < songs.size(); i++)
        content.addChild(new MusicWidget(songs.get(i), list, i));
    }
  }

  private void control(int index, int count, Supplier<String> text, Runnable action) {
    button(
        this,
        text,
        () -> 12 + index * (getWidth() - 18) / count,
        () -> 78,
        () -> Math.max(1, (getWidth() - 18) / count - 6),
        () -> {
          if (!loading) action.run();
        },
        () -> false);
  }

  private void filter(String key, String all, Consumer<String> pick) {
    List<String> labels = new ArrayList<>(), keys = new ArrayList<>();
    labels.add(all);
    keys.add("");
    if (filters != null)
      for (JsonElement item : GogoMusicService.array(filters, key)) {
        labels.add(item.getAsString());
        keys.add(item.getAsString());
      }
    choose(labels, keys, pick);
  }

  private void choose(List<String> labels, List<String> keys, Consumer<String> pick) {
    ++revision;
    loading = false;
    content.getChildren().clear();
    for (int i = 0; i < labels.size(); i++) {
      final String key = keys.get(i), title = labels.get(i);
      button(
          content,
          () -> title,
          () -> 0,
          () -> 0,
          content::getWidth,
          () -> pick.accept(key),
          () -> false);
    }
    button(content, () -> "返回", () -> 0, () -> 0, content::getWidth, this::layout, () -> false);
  }

  private void message(String text) {
    Panel row = new Panel();
    content.addChild(row);
    row.setBeforeRenderCallback(() -> row.setBounds(content.getWidth(), 40));
    label(row, () -> text, () -> 0, () -> 8, row::getWidth);
  }

  private void playlistRow(PlayList list) {
    RoundedRectWidget row = new RoundedRectWidget();
    content.addChild(row);
    row.setBeforeRenderCallback(
        () ->
            row.setBounds(content.getWidth(), 54)
                .setRadius(5)
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND)));
    RoundedImageWidget cover = new RoundedImageWidget(list.getCoverLocation(), 5, 5, 44, 44);
    row.addChild(cover);
    cover.setClickable(false);
    final boolean[] requested = {false};
    cover.setBeforeRenderCallback(
        () -> {
          if (!requested[0] && !list.getCoverUrl().isEmpty()) {
            requested[0] = true;
            Textures.downloadTextureAndLoadAsync(list.getCoverUrl(), list.getCoverLocation());
          }
        });
    label(row, list::getName, () -> 58, () -> 11, () -> Math.max(1, row.getWidth() - 70));
    label(
        row,
        () -> list.getCount() + " 首",
        () -> 58,
        () -> 31,
        () -> Math.max(1, row.getWidth() - 70));
    row.setOnClickCallback(
        (x, y, mouse) -> {
          if (mouse != 0) return false;
          openPlaylist(list);
          return true;
        });
  }

  private void openPlaylist(PlayList list) {
    int expected = ++revision;
    loading = true;
    status = "正在加载…";
    layout();
    MultiThreadingUtil.runAsync(
        () -> {
          try {
            List<Music> tracks =
                GogoMusicService.playlistSongs(platform, list.getPlatformPlaylistId());
            MultiThreadingUtil.runOnMainThread(
                () -> {
                  if (!current(expected)) return;
                  list.setMusics(new java.util.concurrent.CopyOnWriteArrayList<>(tracks));
                  list.setMusicsLoaded(true);
                  list.setMusicsQueried(true);
                  loading = false;
                  status = "";
                  NCMScreen.getInstance().setCurrentPanel(new PlaylistPanel(list));
                });
          } catch (Exception error) {
            MultiThreadingUtil.runOnMainThread(
                () -> {
                  if (!current(expected)) return;
                  loading = false;
                  failed = true;
                  status = "歌单无法打开：" + error.getMessage();
                  layout();
                });
          }
        });
  }

  private static String songSortLabel(String key) {
    return switch (key) {
      case "playCount" -> "播放量";
      case "favCount" -> "收藏量";
      case "heatScore" -> "热度";
      default -> "最新";
    };
  }

  private static String listSortLabel(String key) {
    return switch (key) {
      case "new" -> "最新歌单";
      case "favorite" -> "最多收藏";
      case "songCount" -> "最多歌曲";
      default -> "热门歌单";
    };
  }

  private void button(
      AbstractWidget<?> parent,
      Supplier<String> text,
      DoubleSupplier x,
      DoubleSupplier y,
      DoubleSupplier width,
      Runnable action,
      BooleanSupplier selected) {
    RoundedButtonWidget button = new RoundedButtonWidget(text, FontManager.pf12);
    parent.addChild(button);
    button.setBeforeRenderCallback(
        () -> {
          button.setBounds(x.getAsDouble(), y.getAsDouble(), width.getAsDouble(), 26).setRadius(5);
          button.setColor(
              NCMScreen.getColor(
                  selected.getAsBoolean()
                      ? NCMScreen.ColorType.ACCENT
                      : button.isHovering()
                          ? NCMScreen.ColorType.ELEMENT_HOVER
                          : NCMScreen.ColorType.ELEMENT_BACKGROUND));
          button.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
    button.setOnClickCallback(
        (mx, my, mouse) -> {
          if (mouse != 0) return false;
          action.run();
          return true;
        });
  }

  private void label(
      AbstractWidget<?> parent,
      Supplier<String> text,
      DoubleSupplier x,
      DoubleSupplier y,
      DoubleSupplier width) {
    LabelWidget label = new LabelWidget(text, FontManager.pf12);
    parent.addChild(label);
    label.setClickable(false);
    label.setBeforeRenderCallback(
        () ->
            label
                .setPosition(x.getAsDouble(), y.getAsDouble())
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                .setMaxWidth(width.getAsDouble())
                .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH));
  }
}
