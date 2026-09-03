package uk.gov.justice.laa.dstew.access.command.application.priorauthority;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import uk.gov.justice.laa.dstew.access.command.RetryingCommandDispatcher;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.data.PriorAuthorityDataPayload;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.data.PriorAuthorityDraftStore;
import uk.gov.justice.laa.dstew.access.exception.PriorAuthorityNotInProgressException;
import uk.gov.justice.laa.dstew.access.model.DocumentUploadResponse;
import uk.gov.justice.laa.dstew.access.security.AllowApiCaseworker;
import uk.gov.justice.laa.dstew.access.service.sds.SdsService;

/**
 * Uploads a supporting evidence file to SDS under a newly minted document ID, then attaches it to
 * an in-progress Prior Authority draft.
 */
@Component
public class AttachPriorAuthorityDocumentUseCase {

  private final RetryingCommandDispatcher dispatcher;
  private final PriorAuthorityDraftStore draftStore;
  private final SdsService sdsService;

  /** Creates the use case. */
  public AttachPriorAuthorityDocumentUseCase(
      RetryingCommandDispatcher dispatcher,
      PriorAuthorityDraftStore draftStore,
      SdsService sdsService) {
    this.dispatcher = dispatcher;
    this.draftStore = draftStore;
    this.sdsService = sdsService;
  }

  /**
   * Checks a draft exists for the given ID (failing fast before any upload is attempted), validates
   * the parent application is still granted, uploads the file to SDS under a newly minted document
   * ID, then dispatches the attach command.
   *
   * @param priorAuthorityId the prior-authority submission identifier
   * @param file the file to upload
   * @param occurredAt when the attachment occurred
   * @return the newly minted document ID
   * @throws PriorAuthorityNotInProgressException if no in-progress draft exists for the given ID
   * @throws RuntimeException propagated from validation if the application is not granted
   */
  @AllowApiCaseworker
  public UUID attach(UUID priorAuthorityId, MultipartFile file, Instant occurredAt) {
    PriorAuthorityDataPayload draft =
        draftStore
            .find(priorAuthorityId)
            .orElseThrow(() -> new PriorAuthorityNotInProgressException(priorAuthorityId));
    dispatcher.dispatch(new ValidateApplicationGrantedCommand(draft.applicationId()));

    UUID documentId = UUID.randomUUID();
    DocumentUploadResponse response = sdsService.saveFile(priorAuthorityId, documentId, file);

    dispatcher.dispatch(
        new AttachPriorAuthorityDocumentCommand(
            priorAuthorityId,
            documentId,
            file.getOriginalFilename(),
            file.getSize(),
            extensionOf(file.getOriginalFilename()),
            file.getContentType(),
            response.getChecksum(),
            occurredAt));

    return documentId;
  }

  private static String extensionOf(String filename) {
    if (filename == null) {
      return null;
    }
    int lastDot = filename.lastIndexOf('.');
    return lastDot >= 0 && lastDot < filename.length() - 1 ? filename.substring(lastDot + 1) : null;
  }
}
