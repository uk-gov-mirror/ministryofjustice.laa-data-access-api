package uk.gov.justice.laa.dstew.access.command.application.priorauthority.document;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface UploadedDocumentRepository extends JpaRepository<UploadedDocument, UUID> {}
