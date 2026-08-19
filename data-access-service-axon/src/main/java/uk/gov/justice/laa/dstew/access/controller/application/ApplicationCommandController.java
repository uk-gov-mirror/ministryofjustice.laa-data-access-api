package uk.gov.justice.laa.dstew.access.controller.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import uk.gov.justice.laa.dstew.access.command.application.CreateApplicationCommand;
import uk.gov.justice.laa.dstew.access.command.application.CreateApplicationUseCase;
import uk.gov.justice.laa.dstew.access.command.application.assignment.AssignCaseworkerUseCase;
import uk.gov.justice.laa.dstew.access.command.application.assignment.UnassignCaseworkerUseCase;
import uk.gov.justice.laa.dstew.access.command.application.decision.MakeApplicationDecisionUseCase;
import uk.gov.justice.laa.dstew.access.command.application.note.CreateNoteUseCase;
import uk.gov.justice.laa.dstew.access.command.application.ready.MarkApplicationReadyCommand;
import uk.gov.justice.laa.dstew.access.command.application.ready.ReadyApplicationResult;
import uk.gov.justice.laa.dstew.access.command.application.ready.RecordAutoGrantOutcomeUseCase;
import uk.gov.justice.laa.dstew.access.command.application.update.UpdateApplicationUseCase;
import uk.gov.justice.laa.dstew.access.model.ApplicationCreateRequest;
import uk.gov.justice.laa.dstew.access.model.ApplicationUpdateRequest;
import uk.gov.justice.laa.dstew.access.model.AutoGrantOutcomeRequest;
import uk.gov.justice.laa.dstew.access.model.CaseworkerAssignRequest;
import uk.gov.justice.laa.dstew.access.model.CaseworkerUnassignRequest;
import uk.gov.justice.laa.dstew.access.model.CreateNoteRequest;
import uk.gov.justice.laa.dstew.access.model.MakeDecisionRequest;
import uk.gov.justice.laa.dstew.access.model.ServiceName;

/** HTTP command adapter for Application writes. */
@RestController
@RequestMapping("/api/v0/applications")
public class ApplicationCommandController {

  private final CreateApplicationUseCase createApplicationUseCase;
  private final MakeApplicationDecisionUseCase makeDecisionUseCase;
  private final CreateNoteUseCase createNoteUseCase;
  private final UnassignCaseworkerUseCase unassignCaseworkerUseCase;
  private final AssignCaseworkerUseCase assignCaseworkerUseCase;
  private final RecordAutoGrantOutcomeUseCase recordAutoGrantOutcomeUseCase;
  private final UpdateApplicationUseCase updateApplicationUseCase;
  private final CreateApplicationCommandMapper commandMapper;
  private final MakeDecisionCommandMapper decisionCommandMapper;
  private final AssignCaseworkerRequestMapper assignCaseworkerRequestMapper;
  private final UnassignCaseworkerRequestMapper unassignCaseworkerRequestMapper;
  private final CreateNoteCommandMapper createNoteCommandMapper;
  private final AutoGrantOutcomeCommandMapper autoGrantOutcomeCommandMapper;
  private final UpdateApplicationCommandMapper updateApplicationCommandMapper;

  /** Creates the command adapter. */
  public ApplicationCommandController(
      CreateApplicationUseCase createApplicationUseCase,
      MakeApplicationDecisionUseCase makeDecisionUseCase,
      CreateNoteUseCase createNoteUseCase,
      UnassignCaseworkerUseCase unassignCaseworkerUseCase,
      AssignCaseworkerUseCase assignCaseworkerUseCase,
      RecordAutoGrantOutcomeUseCase recordAutoGrantOutcomeUseCase,
      UpdateApplicationUseCase updateApplicationUseCase,
      CreateApplicationCommandMapper commandMapper,
      MakeDecisionCommandMapper decisionCommandMapper,
      AssignCaseworkerRequestMapper assignCaseworkerRequestMapper,
      UnassignCaseworkerRequestMapper unassignCaseworkerRequestMapper,
      CreateNoteCommandMapper createNoteCommandMapper,
      AutoGrantOutcomeCommandMapper autoGrantOutcomeCommandMapper,
      UpdateApplicationCommandMapper updateApplicationCommandMapper) {
    this.createApplicationUseCase = createApplicationUseCase;
    this.makeDecisionUseCase = makeDecisionUseCase;
    this.createNoteUseCase = createNoteUseCase;
    this.unassignCaseworkerUseCase = unassignCaseworkerUseCase;
    this.assignCaseworkerUseCase = assignCaseworkerUseCase;
    this.recordAutoGrantOutcomeUseCase = recordAutoGrantOutcomeUseCase;
    this.updateApplicationUseCase = updateApplicationUseCase;
    this.commandMapper = commandMapper;
    this.decisionCommandMapper = decisionCommandMapper;
    this.assignCaseworkerRequestMapper = assignCaseworkerRequestMapper;
    this.unassignCaseworkerRequestMapper = unassignCaseworkerRequestMapper;
    this.createNoteCommandMapper = createNoteCommandMapper;
    this.autoGrantOutcomeCommandMapper = autoGrantOutcomeCommandMapper;
    this.updateApplicationCommandMapper = updateApplicationCommandMapper;
  }

  /** Assigns a caseworker to one or more Applications after validating the complete batch. */
  @PostMapping("/assign")
  public ResponseEntity<Void> assignCaseworker(
      @RequestHeader("X-Service-Name") ServiceName serviceName,
      @Valid @RequestBody CaseworkerAssignRequest request) {
    var assignment = assignCaseworkerRequestMapper.toAssignment(request);
    assignCaseworkerUseCase.assign(
        assignment.caseworkerId(),
        assignment.applicationId(),
        assignment.serialisedRequest(),
        assignment.eventDescription());
    return ResponseEntity.ok().build();
  }

  /** Removes the current caseworker assignment from an Application. */
  @PostMapping("/{id}/unassign")
  public ResponseEntity<Void> unassignCaseworker(
      @RequestHeader("X-Service-Name") ServiceName serviceName,
      @PathVariable UUID id,
      @Valid @RequestBody CaseworkerUnassignRequest request) {
    unassignCaseworkerUseCase.execute(unassignCaseworkerRequestMapper.toCommand(id, request));
    return ResponseEntity.ok().build();
  }

  /** Applies an overall and per-proceeding decision to an existing Application version. */
  @PatchMapping("/{id}/decision")
  public ResponseEntity<Void> makeDecision(
      @RequestHeader("X-Service-Name") ServiceName serviceName,
      @PathVariable UUID id,
      @Valid @RequestBody MakeDecisionRequest request) {
    makeDecisionUseCase.execute(decisionCommandMapper.toCommand(id, request));
    return ResponseEntity.noContent().build();
  }

  /** Records either terminal outcome of deciding whether an Application can be auto-granted. */
  @PatchMapping("/{id}/auto-grant-outcome")
  public ResponseEntity<Void> recordAutoGrantOutcome(
      @RequestHeader("X-Service-Name") ServiceName serviceName,
      @PathVariable UUID id,
      @Valid @RequestBody AutoGrantOutcomeRequest request) {
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
  @PostMapping("/{id}/notes")
  public ResponseEntity<Void> createNote(
      @RequestHeader("X-Service-Name") ServiceName serviceName,
      @PathVariable UUID id,
      @Valid @RequestBody CreateNoteRequest request) {
    createNoteUseCase.execute(createNoteCommandMapper.toCommand(id, request));
    return ResponseEntity.noContent().build();
  }

  /** Dispatches create directly to Axon and returns 201 once the projection is readable. */
  @PostMapping
  public ResponseEntity<Void> createApplication(
      @RequestHeader("X-Service-Name") ServiceName serviceName,
      @RequestHeader(value = "X-Schema-Version", required = false, defaultValue = "1") @Min(1)
          int schemaVersion,
      @Valid @RequestBody ApplicationCreateRequest request) {
    CreateApplicationCommand command = commandMapper.toCommand(request, schemaVersion);
    URI location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(command.applicationId())
            .toUri();
    createApplicationUseCase.execute(command);
    return ResponseEntity.created(location).build();
  }

  /** Replaces an existing Application's content and optional status. */
  @PatchMapping("/{id}")
  public ResponseEntity<Void> updateApplication(
      @RequestHeader("X-Service-Name") ServiceName serviceName,
      @PathVariable UUID id,
      @Valid @RequestBody ApplicationUpdateRequest request) {
    updateApplicationUseCase.execute(updateApplicationCommandMapper.toCommand(id, request));
    return ResponseEntity.noContent().build();
  }
}
