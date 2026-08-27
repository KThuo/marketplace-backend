package com.hodi.modules.media;

import java.time.OffsetDateTime;

/** What the media endpoints return. */
public final class MediaDtos {

    private MediaDtos() {}

    public record MediaResponse(
            String id,
            String ownerType,
            String mediaKind,
            String url,
            String contentType,
            Long sizeBytes,
            String caption,
            int sortOrder,
            boolean primary,
            boolean publicVisible,
            OffsetDateTime createdAt) {}
}
