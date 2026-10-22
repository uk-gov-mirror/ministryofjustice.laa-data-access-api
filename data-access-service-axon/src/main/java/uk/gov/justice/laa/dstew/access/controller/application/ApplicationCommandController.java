package uk.gov.justice.laa.dstew.access.controller.application;

import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import uk.gov.justice.laa.dstew.access.api.ApplicationAutoGrantOutcomeCommandApi;
import uk.gov.justice.laa.dstew.access.api.ApplicationCommandApi;
import uk.gov.justice.laa.dstew.access.api.ApplicationDocumentCommandApi;
import uk.gov.justice.laa.dstew.access.command.application.CreateApplicationCommand;
import uk.gov.justice.laa.dstew.access.command.application.CreateApplicationUseCase;
import uk.gov.justice.laa.dstew.access.command.application.decision.MakeApplicationDecisionUseCase;
import uk.gov.justice.laa.dstew.access.command.application.document.UploadDocumentUseCase;
import uk.gov.justice.laa.dstew.access.command.application.linkedgroup.LinkApplicationUseCase;
import uk.gov.justice.laa.dstew.access.command.application.linkedgroup.MakeApplicationLeadUseCase;
import uk.gov.justice.laa.dstew.access.command.application.linkedgroup.UnlinkApplicationUseCase;
import uk.gov.justice.laa.dstew.access.command.application.note.CreateNoteUseCase;
import uk.gov.justice.laa.dstew.access.command.application.ready.MarkApplicationReadyCommand;
import uk.gov.justice.laa.dstew.access.command.application.ready.ReadyApplicationResult;
import uk.gov.justice.laa.dstew.access.command.application.ready.RecordAutoGrantOutcomeUseCase;
import uk.gov.justice.laa.dstew.access.command.application.update.UpdateApplicationUseCase;
import uk.gov.justice.laa.dstew.access.document.DocumentUploadResult;
import uk.gov.justice.laa.dstew.access.model.ApplicationCreateRequest;
import uk.gov.justice.laa.dstew.access.model.ApplicationLinkRequest;
import uk.gov.justice.laa.dstew.access.model.ApplicationUpdateRequest;
import uk.gov.justice.laa.dstew.access.model.AutoGrantOutcomeRequest;
import uk.gov.justice.laa.dstew.access.model.CreateNoteRequest;
import uk.gov.justice.laa.dstew.access.model.DocumentType;
import uk.gov.justice.laa.dstew.access.model.LinkedGroupChangeRequest;
import uk.gov.justice.laa.dstew.access.model.MakeDecisionRequest;
import uk.gov.justice.laa.dstew.access.model.ServiceName;
import uk.gov.justice.laa.dstew.access.model.UploadApplicationDocumentResponse;
import uk.gov.justice.laa.dstew.access.security.AuthenticatedUserId;
import uk.gov.justice.laa.dstew.access.shared.logging.aspects.LogMethodArguments;
import uk.gov.justice.laa.dstew.access.shared.logging.aspects.LogMethodResponse;

/** HTTP command adapter for Application writes. */
@RestController
public class ApplicationCommandController
    implements ApplicationCommandApi,
        ApplicationAutoGrantOutcomeCommandApi,
        ApplicationDocumentCommandApi {

  private final CreateApplicationUseCase createApplicationUseCase;
  private final MakeApplicationDecisionUseCase makeDecisionUseCase;
  private final CreateNoteUseCase createNoteUseCase;
  private final RecordAutoGrantOutcomeUseCase recordAutoGrantOutcomeUseCase;
  private final UpdateApplicationUseCase updateApplicationUseCase;
  private final UploadDocumentUseCase uploadDocumentUseCase;
  private final LinkApplicationUseCase linkApplicationUseCase;
  private final MakeApplicationLeadUseCase makeApplicationLeadUseCase;
  private final UnlinkApplicationUseCase unlinkApplicationUseCase;
  private final CreateApplicationCommandMapper commandMapper;
  private final MakeDecisionCommandMapper decisionCommandMapper;
  private final CreateNoteCommandMapper createNoteCommandMapper;
  private final AutoGrantOutcomeCommandMapper autoGrantOutcomeCommandMapper;
  private final UpdateApplicationCommandMapper updateApplicationCommandMapper;
  private final LinkApplicationCommandMapper linkCommandMapper;
  private final MakeApplicationLeadCommandMapper makeApplicationLeadCommandMapper;
  private final UnlinkApplicationCommandMapper unlinkApplicationCommandMapper;
  private final AuthenticatedUserId authenticatedUserId;

  /** Creates the command adapter. */
  public ApplicationCommandController(
      CreateApplicationUseCase createApplicationUseCase,
      MakeApplicationDecisionUseCase makeDecisionUseCase,
      CreateNoteUseCase createNoteUseCase,
      RecordAutoGrantOutcomeUseCase recordAutoGrantOutcomeUseCase,
      UpdateApplicationUseCase updateApplicationUseCase,
      UploadDocumentUseCase uploadDocumentUseCase,
      LinkApplicationUseCase linkApplicationUseCase,
      MakeApplicationLeadUseCase makeApplicationLeadUseCase,
      UnlinkApplicationUseCase unlinkApplicationUseCase,
      CreateApplicationCommandMapper commandMapper,
      MakeDecisionCommandMapper decisionCommandMapper,
      CreateNoteCommandMapper createNoteCommandMapper,
      AutoGrantOutcomeCommandMapper autoGrantOutcomeCommandMapper,
      UpdateApplicationCommandMapper updateApplicationCommandMapper,
      LinkApplicationCommandMapper linkCommandMapper,
      MakeApplicationLeadCommandMapper makeApplicationLeadCommandMapper,
      UnlinkApplicationCommandMapper unlinkApplicationCommandMapper,
      AuthenticatedUserId authenticatedUserId) {
    this.createApplicationUseCase = createApplicationUseCase;
    this.makeDecisionUseCase = makeDecisionUseCase;
    this.createNoteUseCase = createNoteUseCase;
    this.recordAutoGrantOutcomeUseCase = recordAutoGrantOutcomeUseCase;
    this.updateApplicationUseCase = updateApplicationUseCase;
    this.uploadDocumentUseCase = uploadDocumentUseCase;
    this.linkApplicationUseCase = linkApplicationUseCase;
    this.makeApplicationLeadUseCase = makeApplicationLeadUseCase;
    this.unlinkApplicationUseCase = unlinkApplicationUseCase;
    this.commandMapper = commandMapper;
    this.decisionCommandMapper = decisionCommandMapper;
    this.createNoteCommandMapper = createNoteCommandMapper;
    this.autoGrantOutcomeCommandMapper = autoGrantOutcomeCommandMapper;
    this.updateApplicationCommandMapper = updateApplicationCommandMapper;
    this.linkCommandMapper = linkCommandMapper;
    this.makeApplicationLeadCommandMapper = makeApplicationLeadCommandMapper;
    this.unlinkApplicationCommandMapper = unlinkApplicationCommandMapper;
    this.authenticatedUserId = authenticatedUserId;
  }

  /** Links an Application to an existing Application family group. */
  @Override
  @LogMethodArguments
  @LogMethodResponse
  public ResponseEntity<Void> linkApplication(
      ServiceName serviceName, UUID id, ApplicationLinkRequest request) {
    linkApplicationUseCase.execute(linkCommandMapper.toCommand(id, request));
    return ResponseEntity.noContent().build();
  }

  /** Makes an Application the lead of its linked group. */
  @Override
  @LogMethodArguments
  @LogMethodResponse
  public ResponseEntity<Void> makeApplicationLead(
      ServiceName serviceName, UUID id, LinkedGroupChangeRequest request) {
    makeApplicationLeadUseCase.execute(makeApplicationLeadCommandMapper.toCommand(id, request));
    return ResponseEntity.noContent().build();
  }

  /** Removes an Application from its linked group. */
  @Override
  @LogMethodArguments
  @LogMethodResponse
  public ResponseEntity<Void> unlinkApplication(
      ServiceName serviceName, UUID id, LinkedGroupChangeRequest request) {
    unlinkApplicationUseCase.execute(unlinkApplicationCommandMapper.toCommand(id, request));
    return ResponseEntity.noContent().build();
  }

  /** Applies an overall and per-proceeding decision to an existing Application version. */
  @Override
  @LogMethodArguments
  @LogMethodResponse
  public ResponseEntity<Void> makeDecision(
      ServiceName serviceName, UUID id, MakeDecisionRequest request) {
    makeDecisionUseCase.execute(
        decisionCommandMapper.toCommand(id, authenticatedUserId.get(), request));
    return ResponseEntity.noContent().build();
  }

  /** Records either terminal outcome of deciding whether an Application can be auto-granted. */
  @Override
  @LogMethodArguments
  @LogMethodResponse
  public ResponseEntity<Void> recordAutoGrantOutcome(
      ServiceName serviceName, UUID id, AutoGrantOutcomeRequest request) {
    Object command = autoGrantOutcomeCommandMapper.toCommand(id, request);
    if (command instanceof MarkApplicationReadyCommand readyCommand) {
      ReadyApplicationResult result = recordAutoGrantOutcomeUseCase.recordReady(readyCommand);
      return result == ReadyApplicationResult.RECORDED
          ? ResponseEntity.noContent().build()
          : ResponseEntity.ok().build();
    }
    recordAutoGrantOutcomeUseCase.record(command);
    return ResponseEntity.noContent().build();
  }

  /** Appends a note to an existing Application. */
  @Override
  @LogMethodArguments
  @LogMethodResponse
  public ResponseEntity<Void> createApplicationNotes(
      ServiceName serviceName, UUID id, CreateNoteRequest request) {
    createNoteUseCase.execute(createNoteCommandMapper.toCommand(id, request));
    return ResponseEntity.noContent().build();
  }

  /** Dispatches create directly to Axon and returns 201 once the projection is readable. */
  @Override
  @LogMethodArguments
  @LogMethodResponse
  public ResponseEntity<Void> createApplication(
      ServiceName serviceName, ApplicationCreateRequest request, Integer schemaVersion) {
    CreateApplicationCommand command = commandMapper.toCommand(request, schemaVersion);
    URI location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(command.applicationId())
            .toUri();
    boolean projected = createApplicationUseCase.execute(command);
    return projected
        ? ResponseEntity.created(location).build()
        : ResponseEntity.accepted().location(location).build();
  }

  /** Replaces an existing Application's content and optional status. */
  @Override
  @LogMethodArguments
  @LogMethodResponse
  public ResponseEntity<Void> updateApplication(
      ServiceName serviceName, UUID id, ApplicationUpdateRequest request) {
    updateApplicationUseCase.execute(updateApplicationCommandMapper.toCommand(id, request));
    return ResponseEntity.noContent().build();
  }

  @Override
  @LogMethodArguments
  @LogMethodResponse
  public ResponseEntity<UploadApplicationDocumentResponse> uploadApplicationDocument(
      ServiceName serviceName, UUID id, MultipartFile file, DocumentType documentType) {
    DocumentUploadResult result =
        uploadDocumentUseCase.execute(id, file, documentType.getValue(), serviceName.getValue());
    UploadApplicationDocumentResponse response =
        new UploadApplicationDocumentResponse()
            .documentId(result.documentId())
            .fileName(result.fileName())
            .fileType(result.fileType())
            .contentType(result.contentType())
            .size(result.size())
            .uploadedAt(result.uploadedAt().atOffset(java.time.ZoneOffset.UTC))
            .sourceService(result.sourceService())
            .checksum(result.checksum());
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }
}
