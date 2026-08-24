package com.hodi.security.hashid;

import org.mapstruct.Named;
import org.springframework.stereotype.Component;

/**
 * MapStruct bridge to {@link HashIdUtil}. Per-module mappers declare
 * {@code @Mapper(uses = {HashIdMapper.class})} and annotate every {@code Long id} /
 * {@code Long *Id} field with
 * {@code @Mapping(source = "...", target = "...", qualifiedByName = "encodeId")} so the
 * outbound JSON exposes the per-user-salted HashId, never the raw BIGINT.
 */
@Component
public class HashIdMapper {

    @Named("encodeId")
    public String encode(Long id) {
        return HashIdUtil.encodeId(id);
    }

    @Named("decodeId")
    public Long decode(String hashId) {
        return HashIdUtil.decodeId(hashId);
    }
}
