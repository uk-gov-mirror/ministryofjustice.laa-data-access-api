package uk.gov.justice.laa.dstew.access.command.application.priorauthority.document;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UploadedDocumentRepository extends JpaRepository<UploadedDocument, UUID> {}
