package uk.gov.justice.laa.dstew.access.controller.application;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import uk.gov.justice.laa.dstew.access.api.PriorAuthorityDraftCommandApi;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.AttachPriorAuthorityDocumentUseCase;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.SavePriorAuthorityDraftCommand;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.SavePriorAuthorityDraftUseCase;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.SubmitPriorAuthorityDraftCommand;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.SubmitPriorAuthorityDraftUseCase;
import uk.gov.justice.laa.dstew.access.model.AttachPriorAuthorityDocumentResponse;
import uk.gov.justice.laa.dstew.access.model.SavePriorAuthorityDraftRequest;
import uk.gov.justice.laa.dstew.access.model.SavePriorAuthorityDraftResponse;
import uk.gov.justice.laa.dstew.access.model.ServiceName;
import uk.gov.justice.laa.dstew.access.model.SubmitPriorAuthorityDraftResponse;
import uk.gov.justice.laa.dstew.access.shared.logging.aspects.LogMethodArguments;
import uk.gov.justice.laa.dstew.access.shared.logging.aspects.LogMethodResponse;

/** HTTP command adapter for Prior Authority draft writes. */
@RestController
public class PriorAuthorityDraftCommandController implements PriorAuthorityDraftCommandApi {

  private final SavePriorAuthorityDraftUseCase saveUseCase;
  private final SubmitPriorAuthorityDraftUseCase submitUseCase;
  private final AttachPriorAuthorityDocumentUseCase attachUseCase;
  private final SavePriorAuthorityDraftCommandMapper commandMapper;

  /** Creates the command adapter. */
  public PriorAuthorityDraftCommandController(
      SavePriorAuthorityDraftUseCase saveUseCase,
      SubmitPriorAuthorityDraftUseCase submitUseCase,
      AttachPriorAuthorityDocumentUseCase attachUseCase,
      SavePriorAuthorityDraftCommandMapper commandMapper) {
    this.saveUseCase = saveUseCase;
    this.submitUseCase = submitUseCase;
    this.attachUseCase = attachUseCase;
    this.commandMapper = commandMapper;
  }

  /** Creates a new Prior Authority draft and returns 201 once the projection is readable. */
  @Override
  @LogMethodArguments
  @LogMethodResponse
  @Operation(security = @SecurityRequirement(name = "BearerAuth"))
  public ResponseEntity<SavePriorAuthorityDraftResponse> savePriorAuthorityDraft(
      ServiceName serviceName,
      UUID applicationId,
      SavePriorAuthorityDraftRequest savePriorAuthorityDraftRequest) {
    SavePriorAuthorityDraftCommand command =
        commandMapper.toCreateCommand(applicationId, savePriorAuthorityDraftRequest);
    URI location =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/api/v0/prior-authority/{submissionId}")
            .buildAndExpand(command.submissionId())
            .toUri();
    SavePriorAuthorityDraftResponse response =
        new SavePriorAuthorityDraftResponse(command.submissionId(), OffsetDateTime.now());
    boolean projected = saveUseCase.create(command);
    return projected
        ? ResponseEntity.created(location).body(response)
        : ResponseEntity.accepted().location(location).body(response);
  }

  /** Updates an existing Prior Authority draft and returns 204. */
  @Override
  @LogMethodArguments
  @LogMethodResponse
  @Operation(security = @SecurityRequirement(name = "BearerAuth"))
  public ResponseEntity<Void> updatePriorAuthorityDraft(
      ServiceName serviceName,
      UUID priorAuthorityId,
      SavePriorAuthorityDraftRequest savePriorAuthorityDraftRequest) {
    SavePriorAuthorityDraftCommand command =
        commandMapper.toUpdateCommand(priorAuthorityId, savePriorAuthorityDraftRequest);
    saveUseCase.update(command);
    return ResponseEntity.noContent().build();
  }

  /** Submits an in-progress Prior Authority draft, transitioning it to PENDING. */
  @Override
  @LogMethodArguments
  @LogMethodResponse
  @Operation(security = @SecurityRequirement(name = "BearerAuth"))
  public ResponseEntity<SubmitPriorAuthorityDraftResponse> submitPriorAuthorityDraft(
      ServiceName serviceName, UUID priorAuthorityId) {
    SubmitPriorAuthorityDraftCommand command =
        new SubmitPriorAuthorityDraftCommand(priorAuthorityId, java.time.Instant.now());
    URI location =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/api/v0/prior-authority/{submissionId}")
            .buildAndExpand(priorAuthorityId)
            .toUri();
    SubmitPriorAuthorityDraftResponse response =
        new SubmitPriorAuthorityDraftResponse(priorAuthorityId, OffsetDateTime.now());
    boolean projected = submitUseCase.submit(command);
    return projected
        ? ResponseEntity.created(location).body(response)
        : ResponseEntity.accepted().location(location).body(response);
  }

  /** Attaches a supporting evidence document to an in-progress Prior Authority draft. */
  @Override
  @LogMethodArguments
  @LogMethodResponse
  @Operation(security = @SecurityRequirement(name = "BearerAuth"))
  public ResponseEntity<AttachPriorAuthorityDocumentResponse> attachPriorAuthorityDocument(
      ServiceName serviceName, UUID priorAuthorityId, MultipartFile file) {
    UUID documentId = attachUseCase.attach(priorAuthorityId, file, Instant.now());
    AttachPriorAuthorityDocumentResponse response =
        new AttachPriorAuthorityDocumentResponse(documentId, OffsetDateTime.now());
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }
}
