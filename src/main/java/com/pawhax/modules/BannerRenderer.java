package com.pawhax.modules;

import com.pawhax.PawHax;
import net.minecraft.block.entity.BannerBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.type.BannerPatternsComponent;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class BannerRenderer {

    private static final int BANNER_W = 20;
    private static final int BANNER_H = 40;
    private static final int SCALE    = 6;

    public static byte[] renderBanner(BannerBlockEntity banner) throws IOException {
        DyeColor baseColor = banner.getColorForState();
        BannerPatternsComponent patterns = banner.getPatterns();

        BufferedImage result = createColorLayer(baseColor, BANNER_W, BANNER_H);

        if (patterns != null) {
            for (BannerPatternsComponent.Layer layer : patterns.layers()) {
                Identifier assetId = layer.pattern().value().assetId();
                BufferedImage mask = loadPatternMask(assetId);
                if (mask != null) applyPatternLayer(result, mask, layer.color());
            }
        }

        BufferedImage scaled = scaleImage(result, BANNER_W * SCALE, BANNER_H * SCALE);
        BufferedImage framed = addFrame(scaled);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(framed, "PNG", baos);
        return baos.toByteArray();
    }

    private static BufferedImage createColorLayer(DyeColor color, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int rgb = dyeToRgb(color) | 0xFF000000;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                img.setRGB(x, y, rgb);
        return img;
    }

    private static BufferedImage loadPatternMask(Identifier assetId) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.getResourceManager() == null) return null;
            var resource = mc.getResourceManager().getResource(
                Identifier.of("minecraft", "textures/entity/banner/" + assetId.getPath() + ".png")
            );
            if (resource.isPresent()) return ImageIO.read(resource.get().getInputStream());
        } catch (Exception e) {
            PawHax.LOG.warn("BannerWebhook: Could not load pattern mask for {}: {}", assetId, e.getMessage());
        }
        return null;
    }

    private static void applyPatternLayer(BufferedImage base, BufferedImage mask, DyeColor color) {
        int dyeRgb = dyeToRgb(color);
        int r = (dyeRgb >> 16) & 0xFF;
        int g = (dyeRgb >> 8)  & 0xFF;
        int b = dyeRgb & 0xFF;

        // Banner entity texture is 64x64; the front face starts at UV (1,1)
        // (depth=1 in the model UV layout). Scale proportionally for resource packs.
        float scaleX = mask.getWidth()  / 64.0f;
        float scaleY = mask.getHeight() / 64.0f;

        for (int y = 0; y < BANNER_H; y++) {
            for (int x = 0; x < BANNER_W; x++) {
                int mx = (int) ((x + 1) * scaleX);
                int my = (int) ((y + 1) * scaleY);
                if (mx >= mask.getWidth() || my >= mask.getHeight()) continue;

                int maskPixel = mask.getRGB(mx, my);
                int maskAlpha = (maskPixel >> 24) & 0xFF;
                int maskGray  = (maskPixel >> 16) & 0xFF;

                if (maskAlpha > 0 && maskGray > 10) {
                    float intensity = maskGray / 255f;
                    float alpha = (maskAlpha / 255f) * intensity;

                    int bp = base.getRGB(x, y);
                    int nr = (int) (((bp >> 16) & 0xFF) * (1 - alpha) + r * alpha);
                    int ng = (int) (((bp >> 8)  & 0xFF) * (1 - alpha) + g * alpha);
                    int nb = (int) ((bp & 0xFF)          * (1 - alpha) + b * alpha);
                    base.setRGB(x, y, 0xFF000000 | (nr << 16) | (ng << 8) | nb);
                }
            }
        }
    }

    private static BufferedImage scaleImage(BufferedImage src, int tw, int th) {
        BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(src, 0, 0, tw, th, null);
        g.dispose();
        return out;
    }

    private static BufferedImage addFrame(BufferedImage src) {
        int pad = 12;
        int w = src.getWidth() + pad * 2;
        int h = src.getHeight() + pad * 2;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(30, 30, 30));
        g.fillRoundRect(0, 0, w, h, 10, 10);
        g.setColor(new Color(0, 0, 0, 80));
        g.fillRect(pad + 3, pad + 3, src.getWidth(), src.getHeight());
        g.drawImage(src, pad, pad, null);
        g.setColor(new Color(80, 80, 80));
        g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(1, 1, w - 2, h - 2, 10, 10);
        g.dispose();
        return out;
    }

    private static int dyeToRgb(DyeColor color) {
        return switch (color) {
            case WHITE      -> 0xF9FFFE;
            case ORANGE     -> 0xF9801D;
            case MAGENTA    -> 0xC74EBD;
            case LIGHT_BLUE -> 0x3AB3DA;
            case YELLOW     -> 0xFED83D;
            case LIME       -> 0x80C71F;
            case PINK       -> 0xF38BAA;
            case GRAY       -> 0x474F52;
            case LIGHT_GRAY -> 0x9D9D97;
            case CYAN       -> 0x169C9C;
            case PURPLE     -> 0x8932B8;
            case BLUE       -> 0x3C44AA;
            case BROWN      -> 0x835432;
            case GREEN      -> 0x5E7C16;
            case RED        -> 0xB02E26;
            case BLACK      -> 0x1D1D21;
        };
    }
}
