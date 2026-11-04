package com.muoniumplayer.core.rendering;

import com.muoniumplayer.core.utils.Location;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;

/** 仅为 GOGO/UNM 的静态封面补齐方形画布；原图等比完整显示。 */
public final class BvLibraryCoverLayout {
  private static final int BACKDROP_SIZE = 64;
  private static final int PADDING = 8;
  private static final Kernel BLUR_KERNEL = new Kernel(15, 15, GaussianKernel.generate(15));

  private BvLibraryCoverLayout() {}

  /** 在解码后台线程调用，方形图及其他平台直接返回原对象。 */
  public static BufferedImage prepare(Location location, BufferedImage source) {
    if (source == null
        || location == null
        || !isLibraryCover(location.getResourcePath())
        || source.getWidth() == source.getHeight()) return source;
    int limit = location.getResourcePath().endsWith("/cover_small.png") ? 128 : 512;
    int size = Math.min(limit, Math.max(source.getWidth(), source.getHeight()));
    int padded = BACKDROP_SIZE + PADDING * 2;
    BufferedImage background = new BufferedImage(padded, padded, BufferedImage.TYPE_INT_ARGB);
    Graphics2D bg = background.createGraphics();
    try {
      quality(bg);
      bg.setColor(Color.BLACK);
      bg.fillRect(0, 0, padded, padded);
      int crop = Math.min(source.getWidth(), source.getHeight());
      int sx = (source.getWidth() - crop) / 2;
      int sy = (source.getHeight() - crop) / 2;
      bg.drawImage(source, 0, 0, padded, padded, sx, sy, sx + crop, sy + crop, null);
    } finally {
      bg.dispose();
    }
    BufferedImage blurred =
        new ConvolveOp(BLUR_KERNEL, ConvolveOp.EDGE_NO_OP, null).filter(background, null);
    BufferedImage result = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = result.createGraphics();
    try {
      quality(g);
      g.setColor(Color.BLACK);
      g.fillRect(0, 0, size, size);
      // 只取卷积后的内区，避免模糊边缘出现黑框或清晰边带。
      g.drawImage(
          blurred,
          0,
          0,
          size,
          size,
          PADDING,
          PADDING,
          PADDING + BACKDROP_SIZE,
          PADDING + BACKDROP_SIZE,
          null);
      g.setColor(new Color(0, 0, 0, 64));
      g.fillRect(0, 0, size, size);
      double scale = size / (double) Math.max(source.getWidth(), source.getHeight());
      int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
      int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
      g.drawImage(source, (size - width) / 2, (size - height) / 2, width, height, null);
    } finally {
      g.dispose();
      background.flush();
      blurred.flush();
    }
    return result;
  }

  private static boolean isLibraryCover(String path) {
    boolean song =
        (path.startsWith("muonium/textures/music/gogo_")
                || path.startsWith("muonium/textures/music/unm_"))
            && (path.endsWith("/cover.png") || path.endsWith("/cover_small.png"));
    boolean playlist =
        (path.startsWith("muonium/textures/playlist/gogo/")
                || path.startsWith("muonium/textures/playlist/unm/"))
            && path.endsWith("/cover.png");
    return song || playlist;
  }

  private static void quality(Graphics2D graphics) {
    graphics.setComposite(AlphaComposite.SrcOver);
    graphics.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
  }
}
