package com.muoniumplayer.core.rendering;

import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.render.skija.SkijaGfx;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;
import java.io.InputStream;

/** 两站官网的原始 PNG 品牌标识，保留颜色、底色及比例，随组件淡入淡出。 */
public final class BvLibraryBrandIcons {
  private static final Art GOGO = new Art("/muonium/textures/brands/gogo.png");
  private static final Art UNM = new Art("/muonium/textures/brands/unm.png");

  private BvLibraryBrandIcons() {}

  public static boolean has(MusicPlatform platform) {
    return platform == MusicPlatform.GOGO || platform == MusicPlatform.UNM;
  }

  public static boolean drawIn(
      MusicPlatform platform, double left, double top, double width, double height, int argb) {
    if (!SkijaGfx.active()) return false;
    return draw(SkijaGfx.canvas(), platform, left, top, width, height, argb);
  }

  static boolean draw(
      Canvas canvas,
      MusicPlatform platform,
      double left,
      double top,
      double width,
      double height,
      int argb) {
    Art art = platform == MusicPlatform.GOGO ? GOGO : platform == MusicPlatform.UNM ? UNM : null;
    if (art == null
        || canvas == null
        || !Double.isFinite(left)
        || !Double.isFinite(top)
        || !Double.isFinite(width)
        || !Double.isFinite(height)
        || width <= 0
        || height <= 0) return false;
    Image image = art.image();
    if (image == null) return false;
    double scale = Math.min(width / image.getWidth(), height / image.getHeight());
    float w = (float) (image.getWidth() * scale), h = (float) (image.getHeight() * scale);
    float x = (float) (left + (width - w) * .5), y = (float) (top + (height - h) * .5);
    canvas.save();
    try {
      float radius = Math.min(w, h) * .12f;
      canvas.clipRRect(RRect.makeXYWH(x, y, w, h, radius), true);
      canvas.drawImageRect(
          image,
          Rect.makeWH(image.getWidth(), image.getHeight()),
          Rect.makeXYWH(x, y, w, h),
          SamplingMode.LINEAR,
          SkijaGfx.fill((argb & 0xFF000000) | 0xFFFFFF),
          true);
      return true;
    } finally {
      canvas.restore();
    }
  }

  // 静态内置资源只解码一次；CPU Image 不绑定某一帧的 GL context。
  private static final class Art {
    private final String resource;
    private Image image;
    private boolean attempted;

    private Art(String resource) {
      this.resource = resource;
    }

    private synchronized Image image() {
      if (!attempted) {
        attempted = true;
        try (InputStream input = BvLibraryBrandIcons.class.getResourceAsStream(resource)) {
          if (input != null) image = Image.makeFromEncoded(input.readAllBytes());
        } catch (Exception failure) {
          System.err.println(
              "[MuoniumPlayer] Brand image unavailable: " + resource + " " + failure);
        }
      }
      return image;
    }
  }
}
