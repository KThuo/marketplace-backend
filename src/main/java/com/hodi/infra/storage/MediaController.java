package com.hodi.infra.storage;

import com.hodi.enums.ConfigKey;
import com.hodi.modules.configurations.ConfigurationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Serves locally-stored media.
 *
 * <p>Only reachable in an environment with no bucket configured — a deployed install serves media from object storage
 * and a browser never arrives here. It exists so local development has working images without anyone needing to run
 * MinIO.
 *
 * <p>Public, like the S3 objects it stands in for: a product image on a storefront is fetched by a browser with no
 * session. The key is an unguessable UUID under a tenant prefix, so it is not an enumeration surface.
 *
 * <p>Two things this refuses. A key that escapes the storage root, checked after normalisation rather than by
 * inspecting the string — {@code ..} has too many encodings to blacklist. And any content type of its own devising:
 * the type is inferred from the file and forced to a download for anything not an image, so an HTML file that reached
 * the store cannot be served back as a page from our own origin.
 */
@Slf4j
@RestController
@RequestMapping("/media")
@RequiredArgsConstructor
public class MediaController {

    private final ConfigurationService configs;

    @GetMapping("/**")
    public ResponseEntity<Resource> serve(jakarta.servlet.http.HttpServletRequest request) {
        String key = request.getRequestURI().substring("/media/".length());
        if (key.isBlank()) return ResponseEntity.notFound().build();

        Path root = Path.of(configs.getString(ConfigKey.STORAGE_LOCAL_DIR)).toAbsolutePath().normalize();
        Path file = root.resolve(key).normalize();

        // Checked after normalisation, not by looking for ".." in the string: percent-encoding, overlong UTF-8 and
        // backslashes all give a traversal that a substring test misses, and none of them survive normalisation.
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }

        MediaType contentType = inferType(file);
        var body = ResponseEntity.ok()
                // Immutable: the key contains a UUID, so a given key's bytes never change. A browser and a CDN can
                // both keep it indefinitely.
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                // Forced for anything that is not an image, so a stored HTML document cannot execute on our origin.
                .header("X-Content-Type-Options", "nosniff")
                .contentType(contentType);
        if (!contentType.getType().equals("image")) {
            body = body.header("Content-Disposition", "attachment");
        }
        return body.body(new FileSystemResource(file));
    }

    /** Inferred from the file, never taken from the request. */
    private static MediaType inferType(Path file) {
        try {
            String probed = Files.probeContentType(file);
            return probed == null ? MediaType.APPLICATION_OCTET_STREAM
                    : MediaType.parseMediaType(StorageService.normaliseType(probed));
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
