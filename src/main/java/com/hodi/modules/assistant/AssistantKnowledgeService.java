package com.hodi.modules.assistant;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.common.util.SearchSpecs;
import com.hodi.modules.audit.AuditService;
import com.hodi.security.principal.AuthContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * What the assistant explains, kept by the platform.
 *
 * <p>The answers used to be a map in the provider, so correcting a sentence about stamp duty took a deploy.
 * Now the platform's staff edit them, and the insights page shows which questions went unanswered so they know
 * what to add. A term is matched as a whole word or phrase, never inside another word.
 */
@Service
@RequiredArgsConstructor
public class AssistantKnowledgeService {

    private final AssistantKnowledgeRepository repository;
    private final AuditService audit;

    public record KnowledgeResponse(String reference, String term, String phrasings, String answer,
                                    String linkLabel, String linkTo, boolean active,
                                    OffsetDateTime updatedAt, String updatedBy) {}

    public record KnowledgeRequest(
            @NotBlank(message = "What is the word or phrase?") @Size(max = 80, message = "Keep the term under 80 characters")
            String term,
            @Size(max = 2000, message = "That is a lot of phrasings") String phrasings,
            @NotBlank(message = "Write the answer") @Size(max = 4000, message = "Keep the answer under 4000 characters")
            String answer,
            @Size(max = 80) String linkLabel,
            @Pattern(regexp = "^$|^/[^\\s]*$", message = "A link is a path inside the site, starting with /")
            @Size(max = 255) String linkTo,
            Boolean active) {}

    /** The live entry whose term or phrasing appears in the question as a whole word, longest wording first. */
    @Transactional(readOnly = true)
    public Optional<AssistantKnowledge> match(String question) {
        String q = question.toLowerCase(java.util.Locale.ROOT);
        AssistantKnowledge best = null;
        int bestLength = 0;
        for (AssistantKnowledge k : repository.findLive()) {
            for (String wording : k.wordings()) {
                if (wording.length() > bestLength && Words.has(q, wording)) {
                    best = k;
                    bestLength = wording.length();
                }
            }
        }
        return Optional.ofNullable(best);
    }

    @Transactional(readOnly = true)
    public PagedResponse<KnowledgeResponse> list(PagedDataRequest request) {
        var spec = SearchSpecs.<AssistantKnowledge>allOf(
                SearchSpecs.notArchived(),
                request.getSearch() == null || request.getSearch().isBlank() ? null
                        : (root, query, cb) -> {
                            String like = "%" + request.getSearch().trim().toLowerCase(java.util.Locale.ROOT) + "%";
                            return cb.or(cb.like(cb.lower(root.get("term")), like),
                                    cb.like(cb.lower(root.get("answer")), like));
                        });
        return PagedResponse.from(repository.findAll(spec, request.toPageable(Sort.by("term"))), this::toResponse);
    }

    @Transactional
    public KnowledgeResponse create(KnowledgeRequest request) {
        String term = request.term().trim();
        if (repository.termTaken(term, null)) {
            throw new HodiException("There is already an entry for \"" + term + "\".", HttpStatus.CONFLICT);
        }
        AssistantKnowledge k = AssistantKnowledge.builder()
                .reference(RrnGenerator.generate("KN"))
                .createdBy(AuthContext.username())
                .build();
        apply(k, request);
        repository.save(k);
        audit.record(AppConstant.ACTION_CREATE, "AssistantKnowledge", k.getId(), null, "added \"" + term + "\"");
        return toResponse(k);
    }

    @Transactional
    public KnowledgeResponse update(String reference, KnowledgeRequest request) {
        AssistantKnowledge k = require(reference);
        if (repository.termTaken(request.term().trim(), k.getId())) {
            throw new HodiException("There is already an entry for \"" + request.term().trim() + "\".", HttpStatus.CONFLICT);
        }
        apply(k, request);
        repository.save(k);
        audit.record(AppConstant.ACTION_UPDATE, "AssistantKnowledge", k.getId(), null, "changed \"" + k.getTerm() + "\"");
        return toResponse(k);
    }

    @Transactional
    public void delete(String reference) {
        AssistantKnowledge k = require(reference);
        k.setStatus(AppConstant.STATUS_DELETED);
        k.setStatusFlag(AppConstant.FLAG_DELETED);
        k.setUpdatedBy(AuthContext.username());
        repository.save(k);
        audit.record(AppConstant.ACTION_DELETE, "AssistantKnowledge", k.getId(), null, "removed \"" + k.getTerm() + "\"");
    }

    private void apply(AssistantKnowledge k, KnowledgeRequest r) {
        String label = blank(r.linkLabel());
        String to = blank(r.linkTo());
        if ((label == null) != (to == null)) {
            throw new HodiException("A link needs both what it says and where it goes.", HttpStatus.BAD_REQUEST);
        }
        k.setTerm(r.term().trim());
        k.setPhrasings(blank(r.phrasings()));
        k.setAnswer(r.answer().trim());
        k.setLinkLabel(label);
        k.setLinkTo(to);
        k.setActive(r.active() == null || r.active());
        k.setUpdatedBy(AuthContext.username());
    }

    private AssistantKnowledge require(String reference) {
        return repository.findLiveByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Entry", reference));
    }

    private KnowledgeResponse toResponse(AssistantKnowledge k) {
        return new KnowledgeResponse(k.getReference(), k.getTerm(), k.getPhrasings(), k.getAnswer(), k.getLinkLabel(),
                k.getLinkTo(), k.isActive(), k.getUpdatedAt(), k.getUpdatedBy());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
