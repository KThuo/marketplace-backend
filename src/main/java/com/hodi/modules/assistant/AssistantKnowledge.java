package com.hodi.modules.assistant;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** One thing the assistant explains, written and changed by the platform rather than in code. */
@Entity
@Table(name = "assistant_knowledge")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AssistantKnowledge {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16) private String reference;
    @Column(nullable = false, length = 80) private String term;
    /** Other ways of saying the term, one per line. */
    @Column(columnDefinition = "TEXT") private String phrasings;
    @Column(nullable = false, columnDefinition = "TEXT") private String answer;
    @Column(name = "link_label", length = 80) private String linkLabel;
    @Column(name = "link_to", length = 255) private String linkTo;
    @Builder.Default private boolean active = true;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;
    @CreationTimestamp @Column(name = "created_at", updatable = false) private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    /** The term and every phrasing, lower-cased, longest first so "service charge" beats "charge". */
    public List<String> wordings() {
        Stream<String> extra = phrasings == null ? Stream.empty() : Arrays.stream(phrasings.split("\\R"));
        return Stream.concat(Stream.of(term), extra)
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .distinct()
                .sorted((a, b) -> b.length() - a.length())
                .toList();
    }
}
