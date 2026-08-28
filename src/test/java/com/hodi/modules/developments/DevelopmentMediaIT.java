package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.media.MediaDtos.MediaResponse;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.users.User;
import com.hodi.security.principal.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Photographs against a development and the things under it.
 *
 * <p>Two behaviours worth a test rather than a reading. The cover cache: a card renders from a key on the
 * parent row, so deleting the cover has to promote the next photograph or the card renders a broken image for
 * a reason nobody connects to the deletion. And the ownership check: the schema's composite keys stop a *row*
 * pointing at another development's phase, and say nothing about a *request* naming one.
 */
@SpringBootTest
@Transactional
class DevelopmentMediaIT {

    @Autowired DevelopmentMediaService media;
    @Autowired DevelopmentService developmentService;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentPhaseRepository phases;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.hodi.modules.configurations.ConfigurationService configs;

    private Development development;
    private Development other;
    /** Highest media id before the test ran, so the cleanup can tell this test's rows from everything else. */
    private long mediaWatermark;

    @BeforeEach
    void signInAndBuild() {
        Long tenantId = jdbc.queryForObject(
                "select id from tenants where status <> 5 order by id limit 1", Long.class);
        User user = User.builder().id(1L).username("media-seller").password("x")
                .email("m@example.invalid").firstName("Mia").lastName("Media")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(1L).userId(1L)
                .profileType(AppConstant.ACTOR_SELLER).userTypeCode("SELLER_OWNER")
                .tenantId(tenantId).tenantName("Test Seller")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile, Set.of(), List.of(tenantId), false, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        mediaWatermark = jdbc.queryForObject(
                "select coalesce(max(id), 0) from media_assets", Long.class);

        development = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .name("Photogenic Heights").developmentType("APARTMENT").build());
        other = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .name("Somewhere Else").developmentType("APARTMENT").build());
    }

    /**
     * The rows roll back with the transaction. The files do not.
     *
     * <p>{@code StorageService} writes to the real configured directory, and a store is not transactional, so
     * every run of this class used to leave its photographs behind — forty-odd per full suite, accumulating.
     * Deleting the whole media folder was not an option: it is the same folder a developer's own uploads land
     * in on a dev machine. So the keys this test created are read back while the transaction can still see
     * them, and only those files are removed.
     */
    @AfterEach
    void removeWrittenFilesAndClearContext() {
        try {
            /*
             * Every row above the watermark, not every row owned by the two developments.
             *
             * Filtering on owner_id missed the assets owned by a *phase*, whose owner_id is the phase's —
             * so one file per run still escaped. The watermark needs no such list: inside this transaction the
             * only rows newer than it are ones this test made.
             */
            List<String> keys = jdbc.queryForList(
                    "select storage_key from media_assets where id > ?", String.class, mediaWatermark);
            Path root = Path.of(configs.getString(ConfigKey.STORAGE_LOCAL_DIR)).toAbsolutePath().normalize();
            for (String key : keys) {
                Path file = root.resolve(key).normalize();
                // The same containment check MediaController makes. A key is generated, not supplied, but a
                // test that deletes by path should not be the one place that takes that on trust.
                if (file.startsWith(root)) Files.deleteIfExists(file);
            }
        } catch (Exception e) {
            // A failure to tidy up is not a test failure, and swallowing it silently is how a cleanup
            // quietly stops working. Said out loud, once, without failing the run.
            System.err.println("Could not remove test media files: " + e.getMessage());
        }
        SecurityContextHolder.clearContext();
    }

    private String id(Development d) {
        return com.hodi.security.hashid.HashIdUtil.encodeId(d.getId());
    }

    /**
     * A photograph the size a phone actually produces.
     *
     * <p>Encoded as PNG at four thousand by three thousand with noise in it, which lands around twenty-five
     * megabytes — over the ten-megabyte store ceiling and the eight-megabyte media ceiling that both used to
     * refuse it. Noise matters: a flat colour compresses to nothing and the fixture would prove nothing.
     */
    private MultipartFile hugePhotograph(String name) throws java.io.IOException {
        java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(4000, 3000, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.util.Random random = new java.util.Random(11);
        for (int y = 0; y < 3000; y++) {
            for (int x = 0; x < 4000; x++) {
                int base = 90 + (int) (40 * Math.sin(x / 200.0));
                image.setRGB(x, y, (clamp(base + random.nextInt(50)) << 16)
                        | (clamp(base + random.nextInt(50)) << 8) | clamp(base + random.nextInt(50)));
            }
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return new MockMultipartFile("file", name, "image/png", out.toByteArray());
    }

    private int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private MultipartFile jpeg(String name) {
        // A real two-byte JPEG signature, because StorageService checks the declared type and the store
        // should be handed something that is at least shaped like what it is called.
        return new MockMultipartFile("file", name, "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2});
    }

    @Test
    @DisplayName("the first photograph becomes the cover without anybody choosing it")
    void firstIsCover() {
        MediaResponse first = media.add(id(development), AppConstant.MEDIA_OWNER_DEVELOPMENT, null,
                jpeg("one.jpg"), null, "The site", null);

        assertTrue(first.primary(), "an album whose cover stays blank ships blank");
        assertEquals(AppConstant.MEDIA_KIND_PHOTO, first.mediaKind(), "photo is the default kind");
        assertNotNull(developments.findById(development.getId()).orElseThrow().getPrimaryImageKey(),
                "and the parent's cache carries it, so a card needs no second query");

        MediaResponse second = media.add(id(development), AppConstant.MEDIA_OWNER_DEVELOPMENT, null,
                jpeg("two.jpg"), null, null, null);
        assertFalse(second.primary(), "the second does not take it over");
    }

    @Test
    @DisplayName("deleting the cover promotes the next, rather than leaving a card pointing at nothing")
    void deletingCoverPromotes() {
        MediaResponse first = media.add(id(development), AppConstant.MEDIA_OWNER_DEVELOPMENT, null,
                jpeg("one.jpg"), null, null, null);
        media.add(id(development), AppConstant.MEDIA_OWNER_DEVELOPMENT, null,
                jpeg("two.jpg"), null, null, null);

        media.remove(id(development), AppConstant.MEDIA_OWNER_DEVELOPMENT, null, first.id());

        List<MediaResponse> left = media.list(id(development), AppConstant.MEDIA_OWNER_DEVELOPMENT, null);
        assertEquals(1, left.size());
        assertTrue(left.getFirst().primary(), "the survivor is the cover now");
        assertNotNull(developments.findById(development.getId()).orElseThrow().getPrimaryImageKey(),
                "and the parent's cache follows the promotion");
    }

    @Test
    @DisplayName("removing the last photograph clears the cover rather than keeping a dead key")
    void lastRemovalClearsCover() {
        MediaResponse only = media.add(id(development), AppConstant.MEDIA_OWNER_DEVELOPMENT, null,
                jpeg("only.jpg"), null, null, null);
        media.remove(id(development), AppConstant.MEDIA_OWNER_DEVELOPMENT, null, only.id());

        assertNull(developments.findById(development.getId()).orElseThrow().getPrimaryImageKey(),
                "a key pointing at a deleted object renders as a broken image");
    }

    @Test
    @DisplayName("a phase of another development cannot be named in this one's path")
    void crossDevelopmentPhaseRefused() {
        DevelopmentPhase theirs = phases.save(DevelopmentPhase.builder()
                .developmentId(other.getId()).reference(RrnGenerator.generate("PH"))
                .name("Their foundation").sequenceNo((short) 1).build());
        String theirPhaseId = com.hodi.security.hashid.HashIdUtil.encodeId(theirs.getId());

        assertThrows(ResourceNotFoundException.class, () -> media.add(
                id(development), AppConstant.MEDIA_OWNER_DEVELOPMENT_PHASE, theirPhaseId,
                jpeg("wrong.jpg"), null, null, null));
    }

    @Test
    @DisplayName("a phase needs a child id; a development does not")
    void childIdRequiredForChildren() {
        assertThrows(ResourceNotFoundException.class, () -> media.add(
                id(development), AppConstant.MEDIA_OWNER_DEVELOPMENT_PHASE, null,
                jpeg("nowhere.jpg"), null, null, null));

        // And the development itself needs none.
        assertNotNull(media.add(id(development), AppConstant.MEDIA_OWNER_DEVELOPMENT, null,
                jpeg("fine.jpg"), null, null, null));
    }

    @Test
    @DisplayName("a phase's photographs are its own, not the development's")
    void phaseAlbumIsSeparate() {
        DevelopmentPhase phase = phases.save(DevelopmentPhase.builder()
                .developmentId(development.getId()).reference(RrnGenerator.generate("PH"))
                .name("Foundation").sequenceNo((short) 1).build());
        String phaseId = com.hodi.security.hashid.HashIdUtil.encodeId(phase.getId());

        media.add(id(development), AppConstant.MEDIA_OWNER_DEVELOPMENT, null,
                jpeg("site.jpg"), null, null, null);
        media.add(id(development), AppConstant.MEDIA_OWNER_DEVELOPMENT_PHASE, phaseId,
                jpeg("slab.jpg"), null, "Slab poured", null);

        assertEquals(1, media.list(id(development), AppConstant.MEDIA_OWNER_DEVELOPMENT, null).size());
        assertEquals(1, media.list(id(development), AppConstant.MEDIA_OWNER_DEVELOPMENT_PHASE, phaseId)
                .size());
    }

    @Test
    @DisplayName("an internal photograph is withheld from the public album")
    void publicVisibility() {
        media.add(id(development), AppConstant.MEDIA_OWNER_DEVELOPMENT, null,
                jpeg("brochure.jpg"), null, "For buyers", true);
        media.add(id(development), AppConstant.MEDIA_OWNER_DEVELOPMENT, null,
                jpeg("defects.jpg"), null, "For the lender's file", false);

        assertEquals(2, media.list(id(development), AppConstant.MEDIA_OWNER_DEVELOPMENT, null).size(),
                "the owner sees both");
        List<MediaResponse> forBuyers = media.list(id(development), AppConstant.MEDIA_OWNER_DEVELOPMENT, null)
                .stream().filter(MediaResponse::publicVisible).toList();
        assertEquals(1, forBuyers.size(), "only one of them is a buyer's business");
        assertEquals("For buyers", forBuyers.getFirst().caption());
    }

    @Test
    @DisplayName("an owner type this service does not know is refused with a sentence")
    void unknownOwnerType() {
        com.hodi.common.exception.HodiException thrown = assertThrows(
                com.hodi.common.exception.HodiException.class,
                () -> media.add(id(development), "PROPERTY", null, jpeg("x.jpg"), null, null, null));
        assertTrue(thrown.getMessage().contains("cannot be attached"), thrown.getMessage());
    }

    @Test
    @DisplayName("a twenty-five megabyte photograph is accepted and stored small")
    void oversizedPhotographIsCompressedNotRefused() throws Exception {
        /*
         * The behaviour this exists for. Both ceilings used to refuse this file — the store's ten megabytes
         * and the media module's eight — with "compress it and try again", which asks somebody holding a phone
         * photograph to go and find a tool.
         */
        MultipartFile huge = hugePhotograph("site-visit.png");
        assertTrue(huge.getSize() > 10L * 1024 * 1024,
                "the fixture must exceed the old ceiling or this proves nothing: " + huge.getSize());

        MediaResponse saved = media.add(id(development), AppConstant.MEDIA_OWNER_DEVELOPMENT, null,
                huge, AppConstant.MEDIA_KIND_PHOTO, "From the site visit", true);

        assertNotNull(saved.id(), "accepted");
        assertTrue(saved.sizeBytes() < huge.getSize() / 5,
                "stored far smaller: " + saved.sizeBytes() + " from " + huge.getSize());
        assertEquals("image/jpeg", saved.contentType(),
                "a photograph uploaded as PNG is stored as JPEG — several times smaller for no visible gain");
        assertTrue(saved.url() != null && saved.url().endsWith(".jpg"),
                "and the key's extension follows the format, or a browser refuses the mismatch under "
                        + "nosniff: " + saved.url());
    }
}
