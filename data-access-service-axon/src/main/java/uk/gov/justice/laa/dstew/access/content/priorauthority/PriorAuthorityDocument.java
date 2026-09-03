package uk.gov.justice.laa.dstew.access.content.priorauthority;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;
import uk.gov.justice.laa.dstew.access.ExcludeFromGeneratedCodeCoverage;

/**
 * The mutable, PII-bearing record of a document attached to a Prior Authority draft.
 *
 * <p>Only the real {@code fileName} lives here. The trusted, non-editable identity/technical facts
 * (size, extension, content type, checksum, uploaded-at) live on {@code
 * PriorAuthorityDocumentAttachedEvent} instead, keyed by the same {@code documentId}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@ExcludeFromGeneratedCodeCoverage
public record PriorAuthorityDocument(UUID documentId, String fileName) {}
