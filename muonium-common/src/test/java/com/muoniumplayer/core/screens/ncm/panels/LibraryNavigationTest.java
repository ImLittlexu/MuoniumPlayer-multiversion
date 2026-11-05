package com.muoniumplayer.core.screens.ncm.panels;

import static org.junit.jupiter.api.Assertions.*;

import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.rendering.ui.widgets.TextFieldWidget;
import com.muoniumplayer.core.screens.ncm.NCMPanel;
import com.muoniumplayer.testsupport.ProjectPaths;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 不启动 Minecraft 或网络，执行真实主页分派、缓存页重建和控件布局回调。 */
class LibraryNavigationTest {
  private static Field field(Class<?> type, String name) throws Exception {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private static void call(Object instance, String name) throws Exception {
    Method method = instance.getClass().getDeclaredMethod(name);
    method.setAccessible(true);
    method.invoke(instance);
  }

  private static HomePanel home(MusicPlatform platform, boolean fixed) throws Exception {
    HomePanel home = fixed ? new HomePanel(List.of(), "歌单搜索") : new HomePanel();
    field(HomePanel.class, "platform").set(home, platform);
    return home;
  }

  @Test
  void bothLibrariesResolveBeforeInitializationAndKeepNavigationIdentity() throws Exception {
    for (MusicPlatform platform : List.of(MusicPlatform.GOGO, MusicPlatform.UNM)) {
      HomePanel placeholder = home(platform, false);
      placeholder.setNavigationKey("nav:home:" + platform);
      NCMPanel target = HomePanel.resolvePlatformHome(placeholder);
      assertInstanceOf(GogoLibraryPanel.class, target);
      assertEquals(platform, field(GogoLibraryPanel.class, "platform").get(target));
      assertEquals(placeholder.getNavigationKey(), target.getNavigationKey());
      assertTrue(HomePanel.isDefaultHome(target));
      assertSame(target, HomePanel.resolvePlatformHome(target));
    }
  }

  @Test
  void ordinaryHomesAndFixedResultPagesRemainTheSameObjects() throws Exception {
    for (MusicPlatform platform : MusicPlatform.values()) {
      HomePanel fixed = home(platform, true);
      assertSame(fixed, HomePanel.resolvePlatformHome(fixed));
      assertFalse(HomePanel.isDefaultHome(fixed));
      if (!platform.isBvLibrary()) {
        HomePanel home = home(platform, false);
        assertSame(home, HomePanel.resolvePlatformHome(home));
        assertTrue(HomePanel.isDefaultHome(home));
      }
    }
    assertNull(HomePanel.resolvePlatformHome(null));
    assertFalse(HomePanel.isDefaultHome(null));
    assertFalse(HomePanel.isDefaultHome(new PlaylistPanel(null)));
  }

  @Test
  void finishingPlaylistLoadRestoresCachedListBeforeItEntersHistory() throws Exception {
    for (MusicPlatform platform : List.of(MusicPlatform.GOGO, MusicPlatform.UNM)) {
      GogoLibraryPanel panel = new GogoLibraryPanel(platform);
      PlayList list = new PlayList(123L, "测试歌单", "", 2, 0L, null, "", 0L);
      list.setPlatform(platform);
      field(GogoLibraryPanel.class, "playlists").set(panel, true);
      field(GogoLibraryPanel.class, "lists").set(panel, List.of(list));
      field(GogoLibraryPanel.class, "page").set(panel, 3);
      field(GogoLibraryPanel.class, "keyword").set(panel, "中文搜索");
      field(GogoLibraryPanel.class, "listSort").set(panel, "new");
      field(GogoLibraryPanel.class, "loading").set(panel, true);
      field(GogoLibraryPanel.class, "status").set(panel, "正在加载…");
      call(panel, "layout");
      ScrollPanel loading = (ScrollPanel) field(GogoLibraryPanel.class, "content").get(panel);
      assertInstanceOf(PanelLoadingWidget.class, loading.getChildren().get(0));

      call(panel, "finishPlaylistLoading");
      ScrollPanel restored = (ScrollPanel) field(GogoLibraryPanel.class, "content").get(panel);
      assertNotSame(loading, restored);
      assertEquals(1, restored.getChildren().size());
      assertFalse(restored.getChildren().get(0) instanceof PanelLoadingWidget);
      assertEquals(3, field(GogoLibraryPanel.class, "page").get(panel));
      assertEquals("new", field(GogoLibraryPanel.class, "listSort").get(panel));
      assertFalse((boolean) field(GogoLibraryPanel.class, "loading").get(panel));
      assertEquals("", field(GogoLibraryPanel.class, "status").get(panel));
      TextFieldWidget search = (TextFieldWidget) field(GogoLibraryPanel.class, "search").get(panel);
      assertEquals("中文搜索", search.getText());
    }
  }

  private static String label(AbstractWidget<?> widget) {
    return ((LabelWidget) widget.getChildren().get(0)).getLabel();
  }

  private static void layout(AbstractWidget<?> widget) throws Exception {
    AbstractWidget.RenderCallback callback =
        (AbstractWidget.RenderCallback)
            field(AbstractWidget.class, "beforeRenderCallback").get(widget);
    if (callback != null) callback.onRender();
  }

  @Test
  void searchIsWhiteAndClearButtonFitsAlongsideSearchOnBothTabs() throws Exception {
    for (MusicPlatform platform : List.of(MusicPlatform.GOGO, MusicPlatform.UNM)) {
      for (boolean playlists : List.of(false, true)) {
        GogoLibraryPanel panel = new GogoLibraryPanel(platform);
        field(GogoLibraryPanel.class, "playlists").set(panel, playlists);
        field(GogoLibraryPanel.class, "status").set(panel, "暂无内容");
        call(panel, "layout");
        TextFieldWidget input =
            (TextFieldWidget) field(GogoLibraryPanel.class, "search").get(panel);
        assertEquals(0xFFFFFF, input.getColor().getRGB() & 0xFFFFFF);
        List<AbstractWidget<?>> buttons =
            panel.getChildren().stream()
                .filter(widget -> widget instanceof RoundedButtonWidget)
                .filter(widget -> label(widget).equals("搜索") || label(widget).equals("清空"))
                .toList();
        assertEquals(
            List.of("搜索", "清空"), buttons.stream().map(LibraryNavigationTest::label).toList());
        for (int width : List.of(240, 480, 1024)) {
          panel.setBounds(width, 600);
          layout(input);
          for (AbstractWidget<?> button : buttons) layout(button);
          assertTrue(input.getRelativeX() + input.getWidth() < buttons.get(0).getRelativeX());
          assertTrue(
              buttons.get(0).getRelativeX() + buttons.get(0).getWidth()
                  < buttons.get(1).getRelativeX());
          assertEquals(width - 12, buttons.get(1).getRelativeX() + buttons.get(1).getWidth());
        }
      }
    }
  }

  @Test
  void historyAndSidebarUseResolvedHomeAndClearInvalidatesEarlierSearch() throws Exception {
    String screen =
        Files.readString(
            ProjectPaths.sharedJava().resolve("com/muoniumplayer/core/screens/ncm/NCMScreen.java"));
    for (String marker :
        List.of(
            "public void setCurrentPanel(NCMPanel panel)",
            "public void replaceCurrentPanel(NCMPanel panel)")) {
      int start = screen.indexOf(marker);
      String body = screen.substring(start, screen.indexOf("\n    /**", start));
      assertTrue(
          body.indexOf("HomePanel.resolvePlatformHome(panel)")
              < body.indexOf("this.innerSetCurrentPanel(target, true)"));
      assertTrue(body.contains("this.innerSetCurrentPanel(target, false)"));
      assertFalse(body.contains("this.innerSetCurrentPanel(panel, false)"));
    }
    String panel =
        Files.readString(
            ProjectPaths.sharedJava()
                .resolve("com/muoniumplayer/core/screens/ncm/panels/GogoLibraryPanel.java"));
    assertTrue(
        panel.indexOf("finishPlaylistLoading();")
            < panel.indexOf("setCurrentPanel(new PlaylistPanel(list))"));
    assertTrue(
        panel.replaceAll("\\s+", " ").contains("private void clearSearch() { search.setText(\"\"); submit();"));
    assertTrue(panel.replaceAll("\\s+", " ").contains("keyword = search.getText().trim(); firstPage();"));
    assertTrue(panel.replaceAll("\\s+", " ").contains("page = 1; load();"));
    assertTrue(panel.contains("final int expected = ++revision"));
    String sidebar =
        Files.readString(
            ProjectPaths.sharedJava()
                .resolve("com/muoniumplayer/core/screens/ncm/panels/NavigateBar.java"));
    assertTrue(sidebar.contains("if (HomePanel.isDefaultHome(current)) return"));
    assertTrue(sidebar.contains("selectedItem == null && HomePanel.isDefaultHome(panel)"));
  }
}
