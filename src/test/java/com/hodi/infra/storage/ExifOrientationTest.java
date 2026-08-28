package com.hodi.infra.storage;

import com.hodi.enums.ConfigKey;
import com.hodi.modules.configurations.ConfigurationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The orientation tag, and the photograph that would otherwise come out on its side.
 *
 * <p>Worth a test because the parse is written by hand and its failure is silent: a wrong answer does not
 * throw, it rotates somebody's picture of a building ninety degrees. And because the alternative — trusting a
 * shallow byte walk over a format with optional segments — is exactly the kind of code that works on the file
 * you tested with.
 *
 * <p>The EXIF block is assembled here rather than checked in as a fixture, so what the parser is being asked
 * to read is visible in the test.
 */
class ExifOrientationTest {

    /**
     * A JPEG with an APP1 EXIF segment carrying one tag.
     *
     * <p>Big-endian TIFF, one directory entry: tag 0x0112 (Orientation), type SHORT, one value. The value of a
     * SHORT sits in the first two bytes of the four-byte value field, which is the detail most hand-rolled
     * parsers get wrong.
     */
    private byte[] jpegWithOrientation(int orientation) throws IOException {
        /*
         * Deliberately wider than 320 pixels.
         *
         * ImageCompressor floors the configured edge cap at 320 and the size target at 64 KB, so a smaller
         * fixture is "already within limits" whatever the settings say and the resize never runs. The first
         * version of this test used 120x60 and passed nothing through — the floors were doing their job and
         * the test was measuring my stub instead of the code.
         */
        BufferedImage image = new BufferedImage(1200, 600, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 600; y++) {
            for (int x = 0; x < 1200; x++) {
                // A gradient, so a rotation is detectable from the pixels afterwards.
                image.setRGB(x, y, ((x / 5) % 256) << 16 | ((y / 3) % 256) << 8 | 0x40);
            }
        }
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "jpeg", jpeg));
        byte[] plain = jpeg.toByteArray();

        byte[] tiff = new byte[] {
                'M', 'M', 0x00, 0x2A,                       // big-endian, magic
                0x00, 0x00, 0x00, 0x08,                     // IFD0 begins at offset 8
                0x00, 0x01,                                 // one entry
                0x01, 0x12,                                 // tag: Orientation
                0x00, 0x03,                                 // type: SHORT
                0x00, 0x00, 0x00, 0x01,                     // count: 1
                0x00, (byte) orientation, 0x00, 0x00,       // value, left-aligned in four bytes
                0x00, 0x00, 0x00, 0x00,                     // no next IFD
        };
        byte[] payload = new byte[6 + tiff.length];
        System.arraycopy("Exif\0\0".getBytes(), 0, payload, 0, 6);
        System.arraycopy(tiff, 0, payload, 6, tiff.length);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF); out.write(0xD8);                   // SOI
        out.write(0xFF); out.write(0xE1);                   // APP1
        int len = payload.length + 2;
        out.write((len >> 8) & 0xFF); out.write(len & 0xFF);
        out.write(payload);
        out.write(plain, 2, plain.length - 2);               // the original, minus its own SOI
        return out.toByteArray();
    }

    @Test
    @DisplayName("the orientation tag is found where the spec puts it")
    void readsTheTag() throws IOException {
        for (int orientation = 1; orientation <= 8; orientation++) {
            assertEquals(orientation, ExifOrientation.read(jpegWithOrientation(orientation)),
                    "orientation " + orientation);
        }
    }

    @Test
    @DisplayName("a JPEG with no EXIF at all reads as upright")
    void noExifIsNormal() throws IOException {
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", out);

        assertEquals(ExifOrientation.NORMAL, ExifOrientation.read(out.toByteArray()));
    }

    @Test
    @DisplayName("anything malformed reads as upright rather than guessing")
    void malformedIsNormal() {
        assertEquals(ExifOrientation.NORMAL, ExifOrientation.read(null));
        assertEquals(ExifOrientation.NORMAL, ExifOrientation.read(new byte[0]));
        assertEquals(ExifOrientation.NORMAL, ExifOrientation.read("not a jpeg".getBytes()));
        // A truncated APP1: the length says there is more than there is.
        assertEquals(ExifOrientation.NORMAL,
                ExifOrientation.read(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1,
                        0x00, (byte) 0xFF, 'E', 'x', 'i', 'f'}));
    }

    @Test
    @DisplayName("the axis-swapping orientations are the ones that rotate a quarter turn")
    void knowsWhichSwapAxes() {
        for (int upright : new int[] {1, 2, 3, 4}) {
            assertEquals(false, ExifOrientation.swapsAxes(upright), "orientation " + upright);
        }
        for (int turned : new int[] {5, 6, 7, 8}) {
            assertEquals(true, ExifOrientation.swapsAxes(turned), "orientation " + turned);
        }
    }

    @Test
    @DisplayName("a sideways photograph comes out upright, with its dimensions swapped")
    void compressorRotatesThePixels() throws IOException {
        ConfigurationService configs = mock(ConfigurationService.class);
        // The smallest edge cap the compressor honours, so the landscape fixture is definitely resized.
        when(configs.getInt(ConfigKey.IMAGE_TARGET_KB, 900)).thenReturn(64);
        when(configs.getInt(ConfigKey.IMAGE_MAX_EDGE, 2560)).thenReturn(320);
        when(configs.getInt(ConfigKey.IMAGE_JPEG_QUALITY, 85)).thenReturn(85);
        when(configs.getInt(ConfigKey.IMAGE_JPEG_QUALITY_FLOOR, 65)).thenReturn(65);
        ImageCompressor compressor = new ImageCompressor(configs);

        // Orientation 6: the sensor wrote it landscape, and it should be shown rotated a quarter turn.
        byte[] sideways = jpegWithOrientation(6);
        ImageCompressor.Result result = compressor.compress(sideways, "image/jpeg", "portrait.jpg");

        assertTrue(result.changed(), "the fixture is over the cap, so it should have been worked on");
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(result.bytes()));
        assertNotNull(out);
        /*
         * The source is 1200x600. Rotated a quarter turn it is taller than it is wide — and the stored pixels
         * are upright, so nothing downstream needs to read a tag to display it correctly. Without this the
         * photograph would be served on its side by every viewer that honours EXIF, because the tag is gone
         * after re-encoding.
         */
        assertTrue(out.getHeight() > out.getWidth(),
                "expected a portrait result, got " + out.getWidth() + "x" + out.getHeight());
    }
}
