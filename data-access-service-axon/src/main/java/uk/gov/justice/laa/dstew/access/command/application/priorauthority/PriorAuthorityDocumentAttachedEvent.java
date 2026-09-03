package uk.gov.justice.laa.dstew.access.command.application.priorauthority;

import java.time.Instant;
import java.util.UUID;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.eventhandling.annotation.Event;

/**
 * Trusted, PII-free record of a document attached to a Prior Authority draft. Carries only the
 * identity/technical facts owned by the aggregate; the real filename lives in the mutable draft
 * content instead.
 */
@Event
public record PriorAuthorityDocumentAttachedEvent(
    @EventTag(key = "PriorAuthorityAggregate") UUID submissionId,
    UUID documentId,
    long size,
    String extension,
    String contentType,
    String checksum,
    Instant uploadedAt) {}
