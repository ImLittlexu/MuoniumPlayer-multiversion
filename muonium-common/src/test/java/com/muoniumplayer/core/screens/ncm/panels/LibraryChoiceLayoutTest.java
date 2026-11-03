package com.muoniumplayer.core.screens.ncm.panels;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** 执行真实选项构造、滚动布局及按钮回调，不需要 GL 绘制或网络。 */
class LibraryChoiceLayoutTest {
  private static final String FILTERS =
      "{\"languages\":[\"国语\",\"粤语\",\"英语\"]," + "\"styles\":[\"甜美\",\"流行\",\"摇滚\"]}";

  private static Field field(Class<?> owner, String name) throws Exception {
    Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private static GogoLibraryPanel panel(MusicPlatform platform, ScrollPanel content)
      throws Exception {
    GogoLibraryPanel panel = new GogoLibraryPanel(platform);
    field(GogoLibraryPanel.class, "content").set(panel, content);
    field(GogoLibraryPanel.class, "filters")
        .set(panel, JsonParser.parseString(FILTERS).getAsJsonObject());
    return panel;
  }

  private static ScrollPanel content() {
    ScrollPanel content = new ScrollPanel();
    content.setBounds(320, 90);
    content.setSpacing(4);
    return content;
  }

  private static void filter(GogoLibraryPanel panel, String key, String all, Consumer<String> pick)
      throws Exception {
    Method method =
        GogoLibraryPanel.class.getDeclaredMethod(
            "filter", String.class, String.class, Consumer.class);
    method.setAccessible(true);
    method.invoke(panel, key, all, pick);
  }

  private static void choose(
      GogoLibraryPanel panel, List<String> labels, List<String> keys, Consumer<String> pick)
      throws Exception {
    Method method =
        GogoLibraryPanel.class.getDeclaredMethod("choose", List.class, List.class, Consumer.class);
    method.setAccessible(true);
    method.invoke(panel, labels, keys, pick);
  }

  private static List<String> labels(ScrollPanel content) {
    return content.getChildren().stream()
        .map(row -> ((LabelWidget) row.getChildren().get(0)).getLabel())
        .toList();
  }

  private static void layoutCallbacks(ScrollPanel content) throws Exception {
    content.alignChildren();
    Field callback = field(AbstractWidget.class, "beforeRenderCallback");
    for (AbstractWidget<?> row : content.getChildren()) {
      ((AbstractWidget.RenderCallback) callback.get(row)).onRender();
    }
  }

  private static void assertSeparateRows(ScrollPanel content) {
    for (int i = 0; i < content.getChildren().size(); i++) {
      AbstractWidget<?> row = content.getChildren().get(i);
      assertEquals(26, row.getHeight());
      assertEquals(
          i * 30 - content.actualScrollOffset,
          row.getRelativeY(),
          0.0001,
          "Button callback must preserve the scroll container's row position");
      assertEquals(320, row.getWidth());
    }
  }

  @Test
  void languagesAndStylesAreSeparateClickableRowsForBothPlatforms() throws Exception {
    for (MusicPlatform platform : List.of(MusicPlatform.GOGO, MusicPlatform.UNM)) {
      ScrollPanel content = content();
      GogoLibraryPanel panel = panel(platform, content);
      List<String> picked = new ArrayList<>();
      filter(panel, "languages", "全部语种", picked::add);
      assertEquals(List.of("全部语种", "国语", "粤语", "英语", "返回"), labels(content));
      layoutCallbacks(content);
      assertSeparateRows(content);
      for (int i = 0; i < 4; i++) assertTrue(content.getChildren().get(i).onMouseClicked(0, 0, 0));
      assertEquals(List.of("", "国语", "粤语", "英语"), picked);

      picked.clear();
      filter(panel, "styles", "全部风格", picked::add);
      assertEquals(List.of("全部风格", "甜美", "流行", "摇滚", "返回"), labels(content));
      layoutCallbacks(content);
      assertSeparateRows(content);
      for (int i = 0; i < 4; i++) assertTrue(content.getChildren().get(i).onMouseClicked(0, 0, 0));
      assertEquals(List.of("", "甜美", "流行", "摇滚"), picked);
    }
  }

  @Test
  void everySortOptionRemainsDistinctAfterButtonCallbacksAndPassesItsApiKey() throws Exception {
    ScrollPanel content = content();
    GogoLibraryPanel panel = panel(MusicPlatform.GOGO, content);
    List<String> picked = new ArrayList<>();
    choose(
        panel,
        List.of("最新", "播放量", "收藏量", "热度"),
        List.of("createdAt", "playCount", "favCount", "heatScore"),
        picked::add);
    layoutCallbacks(content);
    assertSeparateRows(content);
    for (int i = 0; i < 4; i++) content.getChildren().get(i).onMouseClicked(0, 0, 0);
    assertEquals(List.of("createdAt", "playCount", "favCount", "heatScore"), picked);
  }

  @Test
  void openingAnyMenuResetsBothOffsetsLeftByTheSongList() throws Exception {
    ScrollPanel content = content();
    GogoLibraryPanel panel = panel(MusicPlatform.UNM, content);
    content.actualScrollOffset = 400;
    content.targetScrollOffset = 420;
    filter(panel, "languages", "全部语种", value -> {});
    assertEquals(0, content.actualScrollOffset);
    assertEquals(0, content.targetScrollOffset);
    layoutCallbacks(content);
    assertEquals(0, content.getChildren().get(0).getRelativeY(), 0.0001);
  }

  @Test
  void longMenuCanScrollWithoutRowsJumpingBackToZero() throws Exception {
    ScrollPanel content = content();
    GogoLibraryPanel panel = panel(MusicPlatform.GOGO, content);
    List<String> keys =
        java.util.stream.IntStream.range(0, 14).mapToObj(i -> "option-" + i).toList();
    choose(panel, keys, keys, value -> {});
    layoutCallbacks(content);
    content.actualScrollOffset = 60;
    content.targetScrollOffset = 60;
    layoutCallbacks(content);
    assertSeparateRows(content);
    assertEquals(-60, content.getChildren().get(0).getRelativeY());
    assertEquals(0, content.getChildren().get(2).getRelativeY());
  }

  @Test
  void emptyFilterStillOffersClearSelectionAndReturnWithoutOverlapping() throws Exception {
    ScrollPanel content = content();
    GogoLibraryPanel panel = panel(MusicPlatform.UNM, content);
    field(GogoLibraryPanel.class, "filters")
        .set(panel, JsonParser.parseString("{}").getAsJsonObject());
    filter(panel, "languages", "全部语种", value -> {});
    assertEquals(List.of("全部语种", "返回"), labels(content));
    layoutCallbacks(content);
    assertSeparateRows(content);
  }
}
