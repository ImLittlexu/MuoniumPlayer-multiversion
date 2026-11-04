package com.muoniumplayer.core.rendering;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.muoniumplayer.core.ncm.music.GogoMusicService;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.utils.Location;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.jupiter.api.Test;

class BvLibraryCoverLayoutTest {
  private static Music song(MusicPlatform platform) throws Exception {
    Music music =
        GogoMusicService.song(
            JsonParser.parseString(
                    "{\"id\":481,\"songName\":\"测试\",\"originalArtist\":[],\"bv\":\"BV1ETsX61EyX\",\"cid\":\"1\"}")
                .getAsJsonObject());
    music.setSource(platform);
    return music;
  }

  private static BufferedImage pattern(int width, int height) {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    try {
      g.setColor(new Color(0x805020));
      g.fillRect(0, 0, width, height);
      g.setColor(Color.RED);
      g.fillRect(0, 0, width / 4, height);
      g.setColor(Color.BLUE);
      g.fillRect(width * 3 / 4, 0, width - width * 3 / 4, height);
      g.setColor(Color.GREEN);
      g.fillOval(width / 2 - 12, height / 2 - 12, 24, 24);
    } finally {
      g.dispose();
    }
    return image;
  }

  private static int[] pixels(BufferedImage image) {
    return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
  }

  @Test
  void wideCoverKeepsTheEntirePictureAndItsProportionsForBothPlatforms() throws Exception {
    for (MusicPlatform platform : List.of(MusicPlatform.GOGO, MusicPlatform.UNM)) {
      BufferedImage source = pattern(160, 90);
      int[] before = pixels(source);
      BufferedImage result =
          BvLibraryCoverLayout.prepare(song(platform).getCoverLocation(), source);
      assertEquals(160, result.getWidth());
      assertEquals(160, result.getHeight());
      assertArrayEquals(
          before,
          result.getRGB(0, 35, 160, 90, null, 0, 160),
          "Full foreground must remain intact, with neither horizontal compression nor cropping");
      assertArrayEquals(
          before, pixels(source), "Never change the original used for palette extraction");
      assertEquals(255, result.getRGB(80, 0) >>> 24);
      assertEquals(255, result.getRGB(80, 159) >>> 24);
      assertNotEquals(
          Color.BLACK.getRGB(),
          result.getRGB(80, 0),
          "Padding uses the image, not plain black bars");
      assertNotEquals(
          source.getRGB(80, 0), result.getRGB(80, 0), "Padding is subdued behind the foreground");
    }
  }

  @Test
  void portraitCoverGetsSidePaddingWithoutCroppingOrStretching() throws Exception {
    BufferedImage source = pattern(90, 160);
    BufferedImage result =
        BvLibraryCoverLayout.prepare(song(MusicPlatform.GOGO).getCoverLocation(), source);
    assertArrayEquals(pixels(source), result.getRGB(35, 0, 90, 160, null, 0, 90));
    assertEquals(160, result.getWidth());
    assertEquals(160, result.getHeight());
  }

  @Test
  void backdropIsActuallyBlurredRatherThanAnUnmodifiedEnlargedDuplicate() throws Exception {
    BufferedImage source = new BufferedImage(160, 90, BufferedImage.TYPE_INT_RGB);
    for (int x = 0; x < 160; x++)
      for (int y = 0; y < 90; y++)
        source.setRGB(x, y, x % 4 < 2 ? Color.WHITE.getRGB() : Color.BLACK.getRGB());
    BufferedImage result =
        BvLibraryCoverLayout.prepare(song(MusicPlatform.UNM).getCoverLocation(), source);
    int gray = (result.getRGB(80, 8) >> 16) & 255;
    assertTrue(
        gray > 20 && gray < 170, "Blur averages the black/white stripes into a softer background");
    assertArrayEquals(pixels(source), result.getRGB(0, 35, 160, 90, null, 0, 160));
  }

  @Test
  void allOtherPlatformsReturnTheOriginalPixelsAndObject() throws Exception {
    BufferedImage source = pattern(160, 90);
    int[] before = pixels(source);
    for (MusicPlatform platform : MusicPlatform.values()) {
      if (platform.isBvLibrary()) continue;
      Music song = song(platform);
      assertSame(
          source, BvLibraryCoverLayout.prepare(song.getCoverLocation(), source), platform.name());
      assertSame(
          source,
          BvLibraryCoverLayout.prepare(song.getSmallCoverLocation(), source),
          platform.name());
    }
    assertArrayEquals(before, pixels(source));
  }

  @Test
  void onlyStaticCoversAreChangedAndSquareInputsRemainUnchanged() throws Exception {
    BufferedImage source = pattern(160, 90);
    Music music = song(MusicPlatform.GOGO);
    assertSame(source, BvLibraryCoverLayout.prepare(music.getDynamicCoverLocation(), source));
    assertSame(source, BvLibraryCoverLayout.prepare(music.getBlurredCoverLocation(), source));
    assertSame(
        source,
        BvLibraryCoverLayout.prepare(
            Location.of("muonium/textures/avatars/gogo_481/cover.png"), source));
    assertSame(source, BvLibraryCoverLayout.prepare(null, source));
    assertNull(BvLibraryCoverLayout.prepare(music.getCoverLocation(), null));
    BufferedImage square = pattern(128, 128);
    assertSame(square, BvLibraryCoverLayout.prepare(music.getCoverLocation(), square));
  }

  @Test
  void fullAndSmallTexturesHaveBoundedSizesAndPublicPlaylistCoversUseTheSameLayout()
      throws Exception {
    BufferedImage source = pattern(1920, 1080);
    for (MusicPlatform platform : List.of(MusicPlatform.GOGO, MusicPlatform.UNM)) {
      Music music = song(platform);
      BufferedImage full = BvLibraryCoverLayout.prepare(music.getCoverLocation(), source);
      BufferedImage small = BvLibraryCoverLayout.prepare(music.getSmallCoverLocation(), source);
      assertEquals(512, full.getWidth());
      assertEquals(512, full.getHeight());
      assertEquals(128, small.getWidth());
      assertEquals(128, small.getHeight());
      String path =
          "muonium/textures/playlist/"
              + platform.name().toLowerCase(java.util.Locale.ROOT)
              + "/42/cover.png";
      assertEquals(512, BvLibraryCoverLayout.prepare(Location.of(path), source).getHeight());
    }
    assertSame(
        source,
        BvLibraryCoverLayout.prepare(
            Location.of("muonium/textures/playlist/netease/42/cover.png"), source));
  }
}
