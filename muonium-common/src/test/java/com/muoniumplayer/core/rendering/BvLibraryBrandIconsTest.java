package com.muoniumplayer.core.rendering;

import static org.junit.jupiter.api.Assertions.*;

import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.rendering.ui.widgets.BrandIconWidget;
import com.muoniumplayer.render.skija.SkijaNatives;
import com.muoniumplayer.testsupport.RequiresSkijaNative;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Surface;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@RequiresSkijaNative
class BvLibraryBrandIconsTest {
  @BeforeAll
  static void loadNative() {
    assertTrue(SkijaNatives.ensureLoaded());
  }

  @Test
  void onlyTheTwoLibrariesGainBitmapMarks() {
    for (MusicPlatform platform : MusicPlatform.values()) {
      assertEquals(platform.isBvLibrary(), BvLibraryBrandIcons.has(platform));
      assertEquals(
          platform.isBvLibrary() || BrandVectorIcons.has(platform), BrandIconWidget.has(platform));
    }
    assertFalse(BvLibraryBrandIcons.has(null));
  }

  @Test
  void bundledLogosAreTheOriginalWebsiteAssets() throws Exception {
    String[] names = {"gogo", "unm"};
    String[] hashes = {
      "ca741810987cc7aaf5f8cd842bf6430ad934ce50f461d3108d021f31065dcabf",
      "5f6936512786a2556f177dcb69a9e11bb6cc6fa88c309e86572b1c7958ac37d5"
    };
    for (int i = 0; i < names.length; i++) {
      try (InputStream in =
          getClass().getResourceAsStream("/muonium/textures/brands/" + names[i] + ".png")) {
        assertNotNull(in);
        assertEquals(
            hashes[i],
            HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())));
      }
    }
  }

  @Test
  void nonSquareSlotContainsAndCentersTheWholeSquareAsset() {
    for (MusicPlatform platform : new MusicPlatform[] {MusicPlatform.GOGO, MusicPlatform.UNM}) {
      try (Surface surface = Surface.makeRasterN32Premul(96, 64);
          Bitmap bitmap = new Bitmap()) {
        surface.getCanvas().clear(0);
        assertTrue(
            BvLibraryBrandIcons.draw(surface.getCanvas(), platform, 0, 8, 96, 48, 0xFFFF0000));
        bitmap.allocPixels(ImageInfo.makeN32Premul(96, 64));
        assertTrue(surface.readPixels(bitmap, 0, 0));
        assertEquals(0, bitmap.getColor(10, 32));
        assertEquals(0, bitmap.getColor(85, 32));
        assertEquals(0, bitmap.getColor(48, 5));
        assertEquals(255, bitmap.getColor(48, 32) >>> 24);
        assertEquals(0, bitmap.getColor(24, 8)); // Rounded corner.
        // RGB must remain neutral even when the widget's brand tint is red.
        int pixel = bitmap.getColor(48, 20);
        int r = (pixel >>> 16) & 255, g = (pixel >>> 8) & 255, b = pixel & 255;
        if (platform == MusicPlatform.GOGO) {
          assertEquals(r, g);
          assertEquals(g, b);
        }
      }
    }
  }

  @Test
  void widgetOpacityFadesTheLogoBackgroundAndMarkTogether() {
    try (Surface surface = Surface.makeRasterN32Premul(64, 64);
        Bitmap bitmap = new Bitmap()) {
      surface.getCanvas().clear(0);
      assertTrue(
          BvLibraryBrandIcons.draw(
              surface.getCanvas(), MusicPlatform.GOGO, 0, 0, 64, 64, 0x80FF0000));
      bitmap.allocPixels(ImageInfo.makeN32Premul(64, 64));
      assertTrue(surface.readPixels(bitmap, 0, 0));
      assertEquals(128, bitmap.getColor(32, 8) >>> 24);
      assertEquals(128, bitmap.getColor(32, 32) >>> 24);
    }
  }

  @Test
  void unsupportedOrInvalidDrawsDoNotTouchTheCanvas() {
    try (Surface surface = Surface.makeRasterN32Premul(32, 32)) {
      assertFalse(
          BvLibraryBrandIcons.draw(surface.getCanvas(), MusicPlatform.NETEASE, 0, 0, 32, 32, -1));
      assertFalse(
          BvLibraryBrandIcons.draw(surface.getCanvas(), MusicPlatform.GOGO, 0, 0, 0, 32, -1));
      assertFalse(
          BvLibraryBrandIcons.draw(
              surface.getCanvas(), MusicPlatform.GOGO, Double.NaN, 0, 32, 32, -1));
      assertFalse(
          BvLibraryBrandIcons.draw(
              surface.getCanvas(), MusicPlatform.UNM, 0, 0, Double.POSITIVE_INFINITY, 32, -1));
      assertFalse(BvLibraryBrandIcons.draw(null, MusicPlatform.UNM, 0, 0, 32, 32, -1));
    }
    assertFalse(BvLibraryBrandIcons.drawIn(MusicPlatform.GOGO, 0, 0, 32, 32, -1));
  }
}
