package com.hodi.infra.storage;

import com.hodi.enums.ConfigKey;
import com.hodi.modules.configurations.ConfigurationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Makes an uploaded photograph small enough to serve, without refusing it.
 *
 * <h2>Compress, never reject</h2>
 *
 * <p>A photograph off a modern phone is eight to twenty megabytes, and the person uploading it has no way to
 * make it smaller — telling them to "compress it and try again" is asking them to go and find a tool. So an
 * oversized image is resized and re-encoded here rather than turned away. The only uploads still refused are
 * ones large enough to threaten the process itself, and that ceiling is far above any real photograph.
 *
 * <h2>Quality is protected in three ways</h2>
 *
 * <p>The long edge is capped rather than the file size chased directly: a 2560-pixel image is sharp on every
 * screen anybody will view it on, and getting there removes most of the bytes without touching the encoder's
 * quality at all. Only if it is still over target does the JPEG quality step down, and it stops at a floor
 * rather than grinding towards a number — an image that will not fit at acceptable quality is stored slightly
 * over target, which is the right trade.
 *
 * <p>The resize is a two-stage one: the decoder subsamples on the way in, then the remainder is done with
 * bilinear interpolation. Subsampling alone is fast and aliases badly; interpolation alone means decoding the
 * full raster, which for a fifty-megapixel image is two hundred megabytes of heap.
 *
 * <h2>What it will not touch</h2>
 *
 * <p>GIFs, because a re-encode would drop the animation. WebP and AVIF, because stock ImageIO cannot decode
 * them and a plugin is a dependency this does not need — they are already efficient formats. PDFs. Anything
 * it cannot decode. In every one of those cases the original passes through unchanged, because the promise
 * is that an upload is never refused, not that it is always shrunk.
 *
 * <p>Vault documents never reach here: they go through their own encrypted store. A title deed is not
 * something to re-encode.
 *
 * <h2>Format</h2>
 *
 * <p>A PNG with transparency stays a PNG — a logo that loses its alpha channel is a logo on a white box.
 * Everything else becomes JPEG, including a PNG without alpha: a photograph saved as PNG is several times
 * larger for no visible gain, and that is the most common way a large upload arrives.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageCompressor {

    private final ConfigurationService configs;

    /** What this can decode. Everything else passes through untouched. */
    private static final java.util.Set<String> COMPRESSIBLE =
            java.util.Set.of("image/jpeg", "image/png");

    /**
     * The result: bytes to store, and what they are.
     *
     * @param bytes       what to write
     * @param contentType what it is now, which may differ from what arrived
     * @param changed     whether anything was done, so the caller can log honestly
     */
    public record Result(byte[] bytes, String contentType, boolean changed) {}

    /**
     * Shrinks the image if it is worth shrinking.
     *
     * <p>Returns the original bytes for anything not worth touching — already small, not an image, an
     * animation, a format we cannot read — so a caller can use the result unconditionally.
     */
    public Result compress(byte[] original, String contentType, String filename) {
        if (original == null || original.length == 0) {
            return new Result(original, contentType, false);
        }
        if (!COMPRESSIBLE.contains(contentType)) {
            log.debug("{} is {}; left as it is", filename, contentType);
            return new Result(original, contentType, false);
        }

        long targetBytes = (long) targetKb() * 1024;
        int maxEdge = maxEdge();

        try {
            int[] size = dimensions(original);
            if (size == null) {
                // Undecodable, or a format whose reader is absent. Storing it is better than refusing it.
                log.info("Could not read the dimensions of {}; storing as uploaded", filename);
                return new Result(original, contentType, false);
            }

            boolean withinSize = original.length <= targetBytes;
            boolean withinEdge = Math.max(size[0], size[1]) <= maxEdge;
            if (withinSize && withinEdge) {
                return new Result(original, contentType, false);
            }

            int orientation = "image/jpeg".equals(contentType)
                    ? ExifOrientation.read(original) : ExifOrientation.NORMAL;

            BufferedImage image = decodeScaled(original, size, maxEdge, orientation);
            if (image == null) {
                log.info("Could not decode {}; storing as uploaded", filename);
                return new Result(original, contentType, false);
            }

            boolean keepPng = "image/png".equals(contentType) && image.getColorModel().hasAlpha();
            Result out = keepPng ? asPng(image) : asJpeg(image, targetBytes);

            /*
             * If the work made it bigger, keep the original.
             *
             * That happens: a small PNG of line art re-encoded as JPEG can grow, and so can an image already
             * compressed harder than our quality floor. Storing the larger of the two would be the opposite
             * of the point.
             */
            if (out.bytes().length >= original.length && withinEdge) {
                log.debug("Compressing {} would not have helped; keeping the original", filename);
                return new Result(original, contentType, false);
            }

            log.info("Compressed {} from {} to {} ({}x{} -> {}x{})", filename,
                    humanBytes(original.length), humanBytes(out.bytes().length),
                    size[0], size[1], image.getWidth(), image.getHeight());
            return out;
        } catch (IOException | RuntimeException e) {
            /*
             * Broad on purpose. Whatever went wrong in the decoder, the upload still has to succeed —
             * refusing somebody's photograph because our resize failed is the outcome this class exists to
             * avoid.
             */
            log.warn("Could not compress {} ({}); storing as uploaded", filename, e.getMessage());
            return new Result(original, contentType, false);
        }
    }

    // ── reading ───────────────────────────────────────────────────────────────

    /** Width and height without decoding the pixels. */
    private int[] dimensions(byte[] data) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            if (in == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return new int[] {reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * Decodes at roughly the size wanted, then finishes the resize smoothly.
     *
     * <p>The decoder's subsampling does the heavy lifting so the full-size raster is never held in memory —
     * a fifty-megapixel photograph is two hundred megabytes as a {@code BufferedImage}, and several of those
     * at once is a process that dies rather than an upload that is slow.
     */
    private BufferedImage decodeScaled(byte[] data, int[] size, int maxEdge, int orientation)
            throws IOException {
        int longEdge = Math.max(size[0], size[1]);
        int subsample = 1;
        // Halve until one more halving would take it under the target: subsampling only removes pixels, and
        // going too far cannot be undone.
        while (longEdge / (subsample * 2) >= maxEdge && subsample < 8) subsample *= 2;

        BufferedImage decoded;
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            if (in == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                ImageReadParam param = reader.getDefaultReadParam();
                if (subsample > 1) param.setSourceSubsampling(subsample, subsample, 0, 0);
                decoded = reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
        if (decoded == null) return null;

        return transform(decoded, maxEdge, orientation);
    }

    /**
     * The last of the resize, and the rotation the EXIF tag asked for, in one pass.
     *
     * <p>One {@code drawImage} rather than a resize followed by a rotate: two passes means interpolating
     * twice, and the second one softens what the first produced.
     */
    private BufferedImage transform(BufferedImage src, int maxEdge, int orientation) {
        double scale = Math.min(1.0, (double) maxEdge / Math.max(src.getWidth(), src.getHeight()));
        int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(src.getHeight() * scale));

        boolean swap = ExifOrientation.swapsAxes(orientation);
        int outW = swap ? h : w;
        int outH = swap ? w : h;

        if (scale == 1.0 && orientation == ExifOrientation.NORMAL) return src;

        boolean alpha = src.getColorModel().hasAlpha();
        BufferedImage out = new BufferedImage(outW, outH,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);

        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (!alpha) {
                // A JPEG has no transparency, so anything not painted would come out black rather than blank.
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, outW, outH);
            }
            g.setTransform(orientationTransform(orientation, w, h));
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /**
     * The eight EXIF orientations as affine transforms.
     *
     * <p>Written out rather than derived, because the mirrored cases are easy to get subtly wrong and a
     * mirrored building is a harder thing to notice than a rotated one.
     */
    private AffineTransform orientationTransform(int orientation, int w, int h) {
        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2 -> { t.scale(-1, 1); t.translate(-w, 0); }                       // mirrored
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }                     // 180
            case 4 -> { t.scale(1, -1); t.translate(0, -h); }                       // mirrored vertically
            case 5 -> { t.rotate(Math.PI / 2); t.scale(1, -1); }                    // mirrored + 90
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }                 // 90 clockwise
            case 7 -> { t.scale(-1, 1); t.translate(-h, 0);
                        t.translate(0, w); t.rotate(-Math.PI / 2); }                // mirrored + 270
            case 8 -> { t.translate(0, w); t.rotate(-Math.PI / 2); }                // 270 clockwise
            default -> { /* 1, and anything unrecognised: already upright */ }
        }
        return t;
    }

    // ── writing ───────────────────────────────────────────────────────────────

    /**
     * Encodes as JPEG, stepping the quality down only as far as the floor.
     *
     * <p>Stops at the first size that fits rather than searching for the smallest, and gives up at the floor
     * rather than going lower: an image that will not reach target at acceptable quality is stored a little
     * over it. A visibly mushy photograph of somebody's building is a worse outcome than a file half a
     * megabyte larger than we wanted.
     */
    private Result asJpeg(BufferedImage image, long targetBytes) throws IOException {
        float quality = startQuality();
        float floor = qualityFloor();
        byte[] best = null;

        while (true) {
            best = writeJpeg(image, quality);
            if (best.length <= targetBytes || quality <= floor) break;
            quality = Math.max(floor, quality - 0.07f);
        }
        if (best.length > targetBytes) {
            log.info("Stored a little over target at quality {}: {} against a {} target",
                    String.format("%.2f", quality), humanBytes(best.length), humanBytes(targetBytes));
        }
        return new Result(best, "image/jpeg", true);
    }

    private byte[] writeJpeg(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("No JPEG writer on this JVM");
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    /** PNG, for the images whose transparency matters. Lossless, so the resize is the only saving. */
    private Result asPng(BufferedImage image) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", bytes)) throw new IOException("No PNG writer on this JVM");
        return new Result(bytes.toByteArray(), "image/png", true);
    }

    // ── settings ──────────────────────────────────────────────────────────────

    private int targetKb() {
        return Math.max(64, configs.getInt(ConfigKey.IMAGE_TARGET_KB, 900));
    }

    private int maxEdge() {
        return Math.max(320, configs.getInt(ConfigKey.IMAGE_MAX_EDGE, 2560));
    }

    private float startQuality() {
        return clampQuality(configs.getInt(ConfigKey.IMAGE_JPEG_QUALITY, 85) / 100f, 0.85f);
    }

    private float qualityFloor() {
        return clampQuality(configs.getInt(ConfigKey.IMAGE_JPEG_QUALITY_FLOOR, 65) / 100f, 0.65f);
    }

    private float clampQuality(float value, float fallback) {
        if (value < 0.3f || value > 1.0f) return fallback;
        return value;
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
