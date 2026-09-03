package uk.gov.justice.laa.dstew.access.command.application.priorauthority;

import java.time.Instant;
import java.util.UUID;
import org.axonframework.messaging.commandhandling.annotation.Command;
import org.axonframework.modelling.annotation.TargetEntityId;

/**
 * Command that attaches an uploaded document to an in-progress Prior Authority draft. Carries both
 * the trusted identity/technical facts (destined for the aggregate's event) and the real filename
 * (destined for the mutable draft content only).
 */
@Command(routingKey = "submissionId")
public record AttachPriorAuthorityDocumentCommand(
    @TargetEntityId UUID submissionId,
    UUID documentId,
    String fileName,
    long size,
    String extension,
    String contentType,
    String checksum,
    Instant occurredAt) {}
