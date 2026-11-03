package com.muoniumplayer.core.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class IMBlockerCompatTest {
  private static final String[] FIELDS = {
    "probed", "available", "minecraftContext", "setPreferredState"
  };
  private final Object[] previous = new Object[FIELDS.length];

  private static Field field(String name) throws Exception {
    Field field = IMBlockerCompat.class.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  @BeforeEach
  void saveBridge() throws Exception {
    for (int i = 0; i < FIELDS.length; i++) previous[i] = field(FIELDS[i]).get(null);
  }

  @AfterEach
  void restoreBridge() throws Exception {
    for (int i = 0; i < FIELDS.length; i++) field(FIELDS[i]).set(null, previous[i]);
  }

  private static void install(FocusContext context) throws Exception {
    field("minecraftContext").set(null, context);
    field("setPreferredState")
        .set(null, FocusContext.class.getMethod("setPreferredState", boolean.class));
    field("available").set(null, true);
    field("probed").set(null, true);
  }

  /** 模拟 IMBlocker 5.6.2.1 的容器状态守卫与焦点归属，窗口开关仅用布尔量记录。 */
  public static final class FocusContext {
    boolean preferred;
    boolean isFocusOwner = true;
    boolean windowImeEnabled;
    int windowChanges;

    public void setPreferredState(boolean enabled) {
      if (preferred != enabled) {
        preferred = enabled;
        if (isFocusOwner) updateWindow();
      }
    }

    void updateWindow() {
      windowImeEnabled = preferred;
      windowChanges++;
    }
  }

  @Test
  void directWindowOverrideReproducesLeakButBridgeLetsScreenMonitorCloseIme() throws Exception {
    FocusContext legacy = new FocusContext();
    legacy.windowImeEnabled = true;
    legacy.setPreferredState(false);
    assertTrue(
        legacy.windowImeEnabled,
        "Legacy direct setState(true) bypasses the container's false state");

    FocusContext fixed = new FocusContext();
    install(fixed);
    IMBlockerCompat.keepIMEOn();
    assertTrue(fixed.preferred, "The request must update IMBlocker's own expected state");
    assertTrue(fixed.windowImeEnabled);
    fixed.setPreferredState(false); // MinecraftScreenMonitor.onScreenChanged(null)
    assertFalse(fixed.windowImeEnabled, "Returning to gameplay must disable IME");
    assertEquals(2, fixed.windowChanges);
  }

  @Test
  void repeatedSearchFocusDoesNotRepeatedlyResetTheNativeImeContext() throws Exception {
    FocusContext context = new FocusContext();
    install(context);
    IMBlockerCompat.keepIMEOn();
    IMBlockerCompat.keepIMEOn();
    IMBlockerCompat.keepIMEOn();
    assertTrue(context.windowImeEnabled);
    assertEquals(1, context.windowChanges);
  }

  @Test
  void changingScreensDoesNotForceOffTheChatTextField() throws Exception {
    FocusContext context = new FocusContext();
    install(context);
    IMBlockerCompat.keepIMEOn();
    context.isFocusOwner = false; // Chat's text field now owns focus and requests IME.
    context.setPreferredState(false);
    assertTrue(context.windowImeEnabled, "The container must not override a focused chat widget");
    assertEquals(1, context.windowChanges);
    context.isFocusOwner = true; // Closing chat restores the non-text container.
    context.updateWindow();
    assertFalse(context.windowImeEnabled);
  }

  @Test
  void backgroundFocusRequestDoesNotOverrideAnotherInputContext() throws Exception {
    FocusContext context = new FocusContext();
    context.isFocusOwner = false;
    install(context);
    IMBlockerCompat.keepIMEOn();
    assertTrue(context.preferred);
    assertFalse(context.windowImeEnabled);
    assertEquals(0, context.windowChanges);
  }

  @Test
  void absentOptionalModIsANoOpAndTheProbeIsRemembered() throws Exception {
    field("probed").set(null, false);
    field("available").set(null, false);
    field("minecraftContext").set(null, null);
    field("setPreferredState").set(null, null);
    assertDoesNotThrow(IMBlockerCompat::keepIMEOn);
    assertEquals(true, field("probed").get(null));
    assertEquals(false, field("available").get(null));
    assertDoesNotThrow(IMBlockerCompat::keepIMEOn);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "MUONIUM_IMBLOCKER_JAR", matches = ".+")
  void exactInstalledJarExposesTheExpectedPublicFocusApiWithoutInitializingNativeCode()
      throws Exception {
    Path jar = Path.of(System.getenv("MUONIUM_IMBLOCKER_JAR"));
    try (URLClassLoader loader =
        new URLClassLoader(new java.net.URL[] {jar.toUri().toURL()}, null)) {
      Class<?> type =
          Class.forName("io.github.reserveword.imblocker.common.gui.FocusContainer", false, loader);
      var context = type.getField("MINECRAFT");
      assertEquals(type, context.getType());
      assertTrue(Modifier.isStatic(context.getModifiers()));
      var method = type.getMethod("setPreferredState", boolean.class);
      assertEquals(void.class, method.getReturnType());
      assertFalse(Modifier.isStatic(method.getModifiers()));
      assertEquals(boolean.class, type.getMethod("getPreferredState").getReturnType());
    }
  }
}
