package com.hodi.infra.storage;

import com.hodi.enums.ConfigKey;
import com.hodi.modules.configurations.ConfigurationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Compressing an upload instead of refusing it.
 *
 * <p>Real bytes throughout — generated images, encoded and decoded by the same {@code ImageIO} the production
 * path uses. A mocked compressor would prove the wiring and nothing about the thing that matters: whether a
 * twelve-megabyte photograph comes out small enough to serve and still looks like the building.
 *
 * <p>No Spring context: the class takes one collaborator and reads four settings, so a stub is quicker and the
 * failures point at the arithmetic rather than at a bean.
 */
class ImageCompressorTest {

    private ImageCompressor compressor;

    @BeforeEach
    void setUp() {
        ConfigurationService configs = mock(ConfigurationService.class);
        when(configs.getInt(ConfigKey.IMAGE_TARGET_KB, 900)).thenReturn(900);
        when(configs.getInt(ConfigKey.IMAGE_MAX_EDGE, 2560)).thenReturn(2560);
        when(configs.getInt(ConfigKey.IMAGE_JPEG_QUALITY, 85)).thenReturn(85);
        when(configs.getInt(ConfigKey.IMAGE_JPEG_QUALITY_FLOOR, 65)).thenReturn(65);
        compressor = new ImageCompressor(configs);
    }

    /**
     * Something that behaves like a photograph rather than a flat colour.
     *
     * <p>A solid rectangle compresses to almost nothing whatever you do to it, so a test built on one would
     * pass while the compressor did nothing at all. Noise plus soft shapes gives a file that actually has to
     * be worked on — and it is deterministic, seeded, so a failure is reproducible.
     */
    private BufferedImage photographLike(int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int base = 90 + (int) (60 * Math.sin(x / 120.0) * Math.cos(y / 90.0));
                int r = clamp(base + random.nextInt(45));
                int g = clamp(base + random.nextInt(45) - 10);
                int b = clamp(base + random.nextInt(45) - 20);
                image.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return image;
    }

    private int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, out), format + " writer missing");
        return out.toByteArray();
    }

    private BufferedImage decode(byte[] bytes) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertNotNull(image, "the result should be a readable image");
        return image;
    }

    // ── the point of the whole thing ──────────────────────────────────────────

    @Test
    @DisplayName("a big photograph is made small enough to serve, not refused")
    void bigPhotographIsCompressed() throws IOException {
        byte[] original = encode(photographLike(5000, 3800), "png");
        assertTrue(original.length > 3_000_000,
                "the fixture has to be genuinely large or this test proves nothing");

        ImageCompressor.Result result = compressor.compress(original, "image/png", "site.png");

        assertTrue(result.changed());
        assertTrue(result.bytes().length < original.length / 4,
                "expected a large saving, got " + result.bytes().length + " from " + original.length);
        assertEquals("image/jpeg", result.contentType(),
                "a photograph saved as PNG becomes JPEG — several times smaller for no visible gain");

        BufferedImage out = decode(result.bytes());
        assertEquals(2560, Math.max(out.getWidth(), out.getHeight()), "capped at the long edge");
        assertEquals(3800.0 / 5000.0, (double) out.getHeight() / out.getWidth(), 0.01,
                "and the aspect ratio survives, or the building is stretched");
    }

    @Test
    @DisplayName("an image already small enough is left exactly as it was")
    void smallImageIsUntouched() throws IOException {
        byte[] original = encode(photographLike(800, 600), "jpeg");

        ImageCompressor.Result result = compressor.compress(original, "image/jpeg", "small.jpg");

        assertFalse(result.changed());
        assertArrayEquals(original, result.bytes(),
                "not re-encoded: every pass through a lossy encoder costs quality for nothing");
    }

    @Test
    @DisplayName("quality is kept high enough that the result is still worth looking at")
    void qualityStaysReasonable() throws IOException {
        byte[] original = encode(photographLike(4000, 3000), "jpeg");
        ImageCompressor.Result result = compressor.compress(original, "image/jpeg", "big.jpg");

        BufferedImage out = decode(result.bytes());
        /*
         * A crude sharpness check: neighbouring pixels in a noisy photograph should still differ. An
         * over-compressed image goes flat in blocks, and this catches a quality floor set absurdly low or a
         * resize that blurred everything away.
         */
        long differing = 0;
        for (int y = 0; y < out.getHeight(); y += 7) {
            for (int x = 0; x < out.getWidth() - 1; x += 7) {
                if (out.getRGB(x, y) != out.getRGB(x + 1, y)) differing++;
            }
        }
        assertTrue(differing > 1000, "the result looks flattened: only " + differing + " differing pixels");
    }

    // ── what it must not touch ────────────────────────────────────────────────

    @Test
    @DisplayName("transparency survives, so a logo does not end up on a white box")
    void transparencyIsKept() throws IOException {
        BufferedImage logo = new BufferedImage(4000, 4000, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = logo.createGraphics();
        g.setColor(new Color(20, 90, 160));
        g.fillOval(200, 200, 3600, 3600);
        g.dispose();

        ImageCompressor.Result result = compressor.compress(encode(logo, "png"), "image/png", "logo.png");

        assertEquals("image/png", result.contentType(), "a PNG with alpha stays a PNG");
        BufferedImage out = decode(result.bytes());
        assertTrue(out.getColorModel().hasAlpha(), "and it still has its alpha channel");
        assertEquals(0, out.getRGB(2, 2) >>> 24, "the corner outside the circle is still transparent");
    }

    @Test
    @DisplayName("a GIF is passed straight through, because re-encoding would drop the animation")
    void gifIsUntouched() {
        byte[] pretend = "GIF89a-not-really-a-gif".getBytes();
        ImageCompressor.Result result = compressor.compress(pretend, "image/gif", "spin.gif");

        assertFalse(result.changed());
        assertArrayEquals(pretend, result.bytes());
        assertEquals("image/gif", result.contentType());
    }

    @Test
    @DisplayName("a format we cannot decode is stored as it arrived rather than refused")
    void undecodableIsStoredAnyway() {
        // WebP and AVIF have no reader on a stock JVM. Whatever this is, it must not become an error.
        byte[] bytes = new byte[40_000];
        new Random(7).nextBytes(bytes);

        ImageCompressor.Result result = compressor.compress(bytes, "image/webp", "photo.webp");

        assertFalse(result.changed());
        assertArrayEquals(bytes, result.bytes(),
                "the promise is that an upload is never refused, not that it is always shrunk");
    }

    @Test
    @DisplayName("bytes that are not an image at all do not throw")
    void garbageDoesNotThrow() {
        byte[] nonsense = "this is not a JPEG, whatever the header claims".getBytes();
        ImageCompressor.Result result = compressor.compress(nonsense, "image/jpeg", "broken.jpg");

        assertFalse(result.changed());
        assertArrayEquals(nonsense, result.bytes(),
                "refusing somebody's upload because our resize failed is the outcome this avoids");
    }

    @Test
    @DisplayName("a PDF is never touched")
    void pdfIsUntouched() {
        byte[] pdf = "%PDF-1.7 ... a title deed ...".getBytes();
        ImageCompressor.Result result = compressor.compress(pdf, "application/pdf", "deed.pdf");

        assertFalse(result.changed());
        assertArrayEquals(pdf, result.bytes());
    }

    @Test
    @DisplayName("compression that would make a file bigger is abandoned")
    void doesNotMakeThingsWorse() throws IOException {
        /*
         * Line art in a small PNG: lossless and already tiny, and a JPEG of it would be larger. The guard
         * exists because the obvious implementation stores whatever the encoder produced.
         */
        BufferedImage plan = new BufferedImage(900, 700, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = plan.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 900, 700);
        g.setColor(Color.BLACK);
        g.drawRect(60, 60, 780, 580);
        g.drawLine(450, 60, 450, 640);
        g.dispose();
        byte[] original = encode(plan, "png");

        ImageCompressor.Result result = compressor.compress(original, "image/png", "plan.png");

        assertTrue(result.bytes().length <= original.length,
                "storing the larger of the two would be the opposite of the point");
    }

    @Test
    @DisplayName("null and empty are handled without ceremony")
    void nullAndEmpty() {
        assertFalse(compressor.compress(null, "image/jpeg", "x.jpg").changed());
        assertFalse(compressor.compress(new byte[0], "image/jpeg", "x.jpg").changed());
    }
}
