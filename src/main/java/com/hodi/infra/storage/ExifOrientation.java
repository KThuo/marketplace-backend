package com.hodi.infra.storage;

import lombok.extern.slf4j.Slf4j;

/**
 * The one EXIF tag that must survive re-encoding: which way up the photograph is.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>A phone does not rotate the pixels when you turn it sideways. It writes them in sensor order and records
 * an orientation tag, and every viewer is expected to honour it. {@code ImageIO} does not: it decodes the raw
 * pixels and discards the metadata, so re-encoding a photograph silently rotates it ninety degrees. Somebody
 * uploads a portrait shot of a building and gets a landscape one lying on its side.
 *
 * <p>So the tag is read before decoding and applied to the pixels during the resize, after which the image
 * needs no tag — it is upright on its own. That is the correct end state anyway; a stored image that depends
 * on metadata to look right is one that breaks in the next tool that ignores it.
 *
 * <h2>Why by hand rather than a library</h2>
 *
 * <p>One tag, in a well-specified place, against adding a metadata dependency for it. The parse is
 * deliberately shallow and every failure returns {@link #NORMAL}: a photograph whose EXIF cannot be read is
 * still a photograph, and guessing a rotation is worse than not rotating.
 */
@Slf4j
final class ExifOrientation {

    private ExifOrientation() {}

    /** The tag's value when the pixels are already the right way up, and the answer whenever unsure. */
    static final int NORMAL = 1;

    /**
     * Reads the orientation from a JPEG's EXIF block.
     *
     * @return 1–8 as defined by EXIF, or {@link #NORMAL} for anything not understood
     */
    static int read(byte[] jpeg) {
        try {
            return parse(jpeg);
        } catch (RuntimeException e) {
            // Never fatal. A malformed or absent EXIF block is not a reason to refuse an upload.
            log.debug("Could not read EXIF orientation: {}", e.getMessage());
            return NORMAL;
        }
    }

    private static int parse(byte[] d) {
        if (d == null || d.length < 4) return NORMAL;
        // A JPEG starts FF D8. Anything else has no EXIF worth looking for here.
        if ((d[0] & 0xFF) != 0xFF || (d[1] & 0xFF) != 0xD8) return NORMAL;

        int i = 2;
        while (i + 4 <= d.length) {
            if ((d[i] & 0xFF) != 0xFF) return NORMAL;              // out of step with the marker stream
            int marker = d[i + 1] & 0xFF;
            if (marker == 0xD9 || marker == 0xDA) return NORMAL;   // end of image, or start of scan data
            int len = ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
            if (len < 2 || i + 2 + len > d.length) return NORMAL;

            // APP1 carries EXIF, identified by "Exif\0\0" at the top of the segment.
            if (marker == 0xE1 && len >= 8
                    && d[i + 4] == 'E' && d[i + 5] == 'x' && d[i + 6] == 'i' && d[i + 7] == 'f') {
                return fromTiff(d, i + 10, i + 2 + len);
            }
            i += 2 + len;
        }
        return NORMAL;
    }

    /** The TIFF header inside APP1: a byte order, a magic number, then the offset of the first directory. */
    private static int fromTiff(byte[] d, int start, int end) {
        if (start + 8 > end) return NORMAL;
        boolean big;
        if (d[start] == 'M' && d[start + 1] == 'M') big = true;
        else if (d[start] == 'I' && d[start + 1] == 'I') big = false;
        else return NORMAL;

        int ifd = start + (int) num(d, start + 4, 4, big);
        if (ifd + 2 > end) return NORMAL;

        int count = (int) num(d, ifd, 2, big);
        for (int f = 0; f < count; f++) {
            int entry = ifd + 2 + f * 12;
            if (entry + 12 > end) return NORMAL;
            if (num(d, entry, 2, big) == 0x0112) {                 // Orientation
                int value = (int) num(d, entry + 8, 2, big);
                return value >= 1 && value <= 8 ? value : NORMAL;
            }
        }
        return NORMAL;
    }

    private static long num(byte[] d, int at, int bytes, boolean big) {
        long v = 0;
        for (int k = 0; k < bytes; k++) {
            int b = d[at + (big ? k : bytes - 1 - k)] & 0xFF;
            v = (v << 8) | b;
        }
        return v;
    }

    /** Whether this orientation swaps width and height, which the target size has to account for. */
    static boolean swapsAxes(int orientation) {
        return orientation == 5 || orientation == 6 || orientation == 7 || orientation == 8;
    }
}
