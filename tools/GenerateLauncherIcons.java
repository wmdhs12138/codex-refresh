import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/** Reproducible raster export for the adaptive and fallback launcher assets. */
public final class GenerateLauncherIcons {
    private static final Map<String, Double> DENSITIES = new LinkedHashMap<>();

    static {
        DENSITIES.put("mdpi", 1.0);
        DENSITIES.put("hdpi", 1.5);
        DENSITIES.put("xhdpi", 2.0);
        DENSITIES.put("xxhdpi", 3.0);
        DENSITIES.put("xxxhdpi", 4.0);
    }

    private GenerateLauncherIcons() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("foreground.png res-directory preview.png");
        }
        BufferedImage foreground = ImageIO.read(new File(args[0]));
        if (foreground == null || !foreground.getColorModel().hasAlpha()) {
            throw new IllegalArgumentException("Foreground must be a PNG with a real alpha channel");
        }
        File res = new File(args[1]);
        for (Map.Entry<String, Double> density : DENSITIES.entrySet()) {
            double scale = density.getValue();
            int adaptiveSize = (int) Math.round(108 * scale);
            int legacySize = (int) Math.round(48 * scale);
            File directory = new File(res, "mipmap-" + density.getKey());
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IllegalStateException("Cannot create " + directory);
            }
            write(renderForeground(foreground, adaptiveSize), new File(directory, "ic_launcher_flash_foreground.png"));
            write(renderLegacy(foreground, legacySize, false), new File(directory, "ic_launcher_flash.png"));
            write(renderLegacy(foreground, legacySize, true), new File(directory, "ic_launcher_flash_round.png"));
        }
        write(renderLegacy(foreground, 512, false), new File(args[2]));
    }

    private static BufferedImage renderForeground(BufferedImage source, int size) {
        BufferedImage target = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        quality(graphics);
        int[] bounds = alphaBounds(source);
        int width = bounds[2] - bounds[0] + 1;
        int height = bounds[3] - bounds[1] + 1;
        double fit = Math.min(size * (66.0 / 108.0) / width, size * (66.0 / 108.0) / height);
        int outputWidth = Math.max(1, (int) Math.round(width * fit));
        int outputHeight = Math.max(1, (int) Math.round(height * fit));
        int x = (size - outputWidth) / 2;
        int y = (size - outputHeight) / 2;
        graphics.drawImage(
            source,
            x,
            y,
            x + outputWidth,
            y + outputHeight,
            bounds[0],
            bounds[1],
            bounds[2] + 1,
            bounds[3] + 1,
            null
        );
        graphics.dispose();
        return target;
    }

    private static BufferedImage renderLegacy(BufferedImage source, int size, boolean circle) {
        BufferedImage target = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        quality(graphics);
        if (circle) {
            graphics.setClip(new Ellipse2D.Double(0, 0, size, size));
        } else {
            double radius = size * 0.32;
            graphics.setClip(new RoundRectangle2D.Double(0, 0, size, size, radius, radius));
        }
        graphics.setPaint(new LinearGradientPaint(
            0,
            0,
            size,
            size,
            new float[] {0f, 0.52f, 1f},
            new Color[] {new Color(0x25B9FF), new Color(0x1759F0), new Color(0x784CFF)}
        ));
        graphics.fillRect(0, 0, size, size);
        BufferedImage mark = renderForeground(source, size);
        graphics.setComposite(AlphaComposite.SrcOver);
        graphics.drawImage(mark, 0, 0, null);
        graphics.dispose();
        return target;
    }

    private static int[] alphaBounds(BufferedImage image) {
        int minX = image.getWidth();
        int minY = image.getHeight();
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = image.getRGB(x, y) >>> 24;
                if (alpha >= 24) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < minX || maxY < minY) throw new IllegalArgumentException("Foreground is empty");
        return new int[] {minX, minY, maxX, maxY};
    }

    private static void quality(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    private static void write(BufferedImage image, File output) throws Exception {
        File parent = output.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Cannot create " + parent);
        }
        if (!ImageIO.write(image, "png", output)) {
            throw new IllegalStateException("No PNG writer available");
        }
    }
}
