package uk.gov.justice.laa.dstew.access.controller.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import uk.gov.justice.laa.dstew.access.command.application.CreateApplicationCommand;
import uk.gov.justice.laa.dstew.access.command.application.CreateApplicationUseCase;
import uk.gov.justice.laa.dstew.access.command.application.assignment.AssignCaseworkerUseCase;
import uk.gov.justice.laa.dstew.access.command.application.assignment.CaseworkerAssignment;
import uk.gov.justice.laa.dstew.access.command.application.assignment.UnassignCaseworkerFromApplicationCommand;
import uk.gov.justice.laa.dstew.access.command.application.assignment.UnassignCaseworkerUseCase;
import uk.gov.justice.laa.dstew.access.command.application.decision.MakeApplicationDecisionCommand;
import uk.gov.justice.laa.dstew.access.command.application.decision.MakeApplicationDecisionUseCase;
import uk.gov.justice.laa.dstew.access.command.application.note.CreateNoteCommand;
import uk.gov.justice.laa.dstew.access.command.application.note.CreateNoteUseCase;
import uk.gov.justice.laa.dstew.access.command.application.ready.MarkApplicationReadyCommand;
import uk.gov.justice.laa.dstew.access.command.application.ready.ReadyApplicationResult;
import uk.gov.justice.laa.dstew.access.command.application.ready.RecordAutoGrantOutcomeUseCase;
import uk.gov.justice.laa.dstew.access.command.application.update.UpdateApplicationCommand;
import uk.gov.justice.laa.dstew.access.command.application.update.UpdateApplicationUseCase;
import uk.gov.justice.laa.dstew.access.model.AutoGrantOutcome;
import uk.gov.justice.laa.dstew.access.model.ManualOutcomeRequest;

/** Verifies that each controller endpoint delegates to the appropriate use case. */
class ApplicationCommandControllerTest {

  private CreateApplicationUseCase createApplicationUseCase;
  private MakeApplicationDecisionUseCase makeDecisionUseCase;
  private CreateNoteUseCase createNoteUseCase;
  private UnassignCaseworkerUseCase unassignCaseworkerUseCase;
  private AssignCaseworkerUseCase assignCaseworkerUseCase;
  private RecordAutoGrantOutcomeUseCase recordAutoGrantOutcomeUseCase;
  private UpdateApplicationUseCase updateApplicationUseCase;
  private CreateApplicationCommandMapper commandMapper;
  private MakeDecisionCommandMapper decisionCommandMapper;
  private AssignCaseworkerRequestMapper assignCaseworkerRequestMapper;
  private UnassignCaseworkerRequestMapper unassignCaseworkerRequestMapper;
  private CreateNoteCommandMapper createNoteCommandMapper;
  private AutoGrantOutcomeCommandMapper autoGrantOutcomeCommandMapper;
  private UpdateApplicationCommandMapper updateApplicationCommandMapper;
  private ApplicationCommandController controller;

  @BeforeEach
  void setUp() {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    createApplicationUseCase = mock(CreateApplicationUseCase.class);
    makeDecisionUseCase = mock(MakeApplicationDecisionUseCase.class);
    createNoteUseCase = mock(CreateNoteUseCase.class);
    unassignCaseworkerUseCase = mock(UnassignCaseworkerUseCase.class);
    assignCaseworkerUseCase = mock(AssignCaseworkerUseCase.class);
    recordAutoGrantOutcomeUseCase = mock(RecordAutoGrantOutcomeUseCase.class);
    updateApplicationUseCase = mock(UpdateApplicationUseCase.class);
    commandMapper = mock(CreateApplicationCommandMapper.class);
    decisionCommandMapper = mock(MakeDecisionCommandMapper.class);
    assignCaseworkerRequestMapper = mock(AssignCaseworkerRequestMapper.class);
    unassignCaseworkerRequestMapper = mock(UnassignCaseworkerRequestMapper.class);
    createNoteCommandMapper = mock(CreateNoteCommandMapper.class);
    autoGrantOutcomeCommandMapper = mock(AutoGrantOutcomeCommandMapper.class);
    updateApplicationCommandMapper = mock(UpdateApplicationCommandMapper.class);
    controller =
        new ApplicationCommandController(
            createApplicationUseCase,
            makeDecisionUseCase,
            createNoteUseCase,
            unassignCaseworkerUseCase,
            assignCaseworkerUseCase,
            recordAutoGrantOutcomeUseCase,
            updateApplicationUseCase,
            commandMapper,
            decisionCommandMapper,
            assignCaseworkerRequestMapper,
            unassignCaseworkerRequestMapper,
            createNoteCommandMapper,
            autoGrantOutcomeCommandMapper,
            updateApplicationCommandMapper);
  }

  @AfterEach
  void tearDown() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void givenReadinessRecorded_whenMarkApplicationReady_thenReturnsNoContent() {
    UUID id = UUID.randomUUID();
    ManualOutcomeRequest request = new ManualOutcomeRequest(AutoGrantOutcome.MANUAL);
    MarkApplicationReadyCommand command =
        new MarkApplicationReadyCommand(id, 2L, "{}", Instant.now());
    when(autoGrantOutcomeCommandMapper.toCommand(id, request)).thenReturn(command);
    when(recordAutoGrantOutcomeUseCase.recordReady(command))
        .thenReturn(ReadyApplicationResult.RECORDED);

    assertThat(controller.recordAutoGrantOutcome(null, id, request).getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);
  }

  @Test
  void givenReadinessAlreadyRecorded_whenMarkApplicationReady_thenReturnsOk() {
    UUID id = UUID.randomUUID();
    ManualOutcomeRequest request = new ManualOutcomeRequest(AutoGrantOutcome.MANUAL);
    MarkApplicationReadyCommand command =
        new MarkApplicationReadyCommand(id, 2L, "{}", Instant.now());
    when(autoGrantOutcomeCommandMapper.toCommand(id, request)).thenReturn(command);
    when(recordAutoGrantOutcomeUseCase.recordReady(command))
        .thenReturn(ReadyApplicationResult.ALREADY_RECORDED);

    assertThat(controller.recordAutoGrantOutcome(null, id, request).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  @Test
  void givenRequest_whenUpdateApplication_thenDelegatesToUseCase() {
    UUID id = UUID.randomUUID();
    UpdateApplicationCommand command = mock(UpdateApplicationCommand.class);
    when(updateApplicationCommandMapper.toCommand(id, null)).thenReturn(command);
    controller.updateApplication(null, id, null);
    verify(updateApplicationUseCase).execute(command);
  }

  @Test
  void givenProjectedResult_whenCreateApplication_thenDelegatesToUseCaseAndReturns201() {
    CreateApplicationCommand command = stubCreateCommand();
    when(commandMapper.toCommand(any(), anyInt())).thenReturn(command);
    when(createApplicationUseCase.execute(command)).thenReturn(true);
    ResponseEntity<Void> response = controller.createApplication(null, 1, null);
    verify(createApplicationUseCase).execute(command);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  @Disabled("Projection no longer used so always returns true")
  @Test
  void givenTimeoutResult_whenCreateApplication_thenDelegatesToUseCaseAndReturns202() {
    CreateApplicationCommand command = stubCreateCommand();
    when(commandMapper.toCommand(any(), anyInt())).thenReturn(command);
    when(createApplicationUseCase.execute(command)).thenReturn(false);
    ResponseEntity<Void> response = controller.createApplication(null, 1, null);
    verify(createApplicationUseCase).execute(command);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
  }

  @Test
  void givenRequest_whenMakeDecision_thenDelegatesToUseCase() {
    UUID id = UUID.randomUUID();
    MakeApplicationDecisionCommand command = mock(MakeApplicationDecisionCommand.class);
    when(decisionCommandMapper.toCommand(id, null)).thenReturn(command);
    controller.makeDecision(null, id, null);
    verify(makeDecisionUseCase).execute(command);
  }

  @Test
  void givenRequest_whenCreateNote_thenDelegatesToUseCase() {
    UUID id = UUID.randomUUID();
    CreateNoteCommand command = mock(CreateNoteCommand.class);
    when(createNoteCommandMapper.toCommand(id, null)).thenReturn(command);
    controller.createNote(null, id, null);
    verify(createNoteUseCase).execute(command);
  }

  @Test
  void givenRequest_whenUnassignCaseworker_thenDelegatesToUseCase() {
    UUID id = UUID.randomUUID();
    UnassignCaseworkerFromApplicationCommand command =
        mock(UnassignCaseworkerFromApplicationCommand.class);
    when(unassignCaseworkerRequestMapper.toCommand(id, null)).thenReturn(command);
    controller.unassignCaseworker(null, id, null);
    verify(unassignCaseworkerUseCase).execute(command);
  }

  @Test
  void givenRequest_whenAssignCaseworker_thenDelegatesToUseCase() {
    CaseworkerAssignment assignment =
        new CaseworkerAssignment(UUID.randomUUID(), UUID.randomUUID(), "{}", "desc");
    when(assignCaseworkerRequestMapper.toAssignment(any())).thenReturn(assignment);
    controller.assignCaseworker(null, null);
    verify(assignCaseworkerUseCase)
        .assign(
            assignment.caseworkerId(),
            assignment.applicationId(),
            assignment.serialisedRequest(),
            assignment.eventDescription());
    verify(makeDecisionUseCase, never()).execute(any());
    verify(createNoteUseCase, never()).execute(any());
    verify(unassignCaseworkerUseCase, never()).execute(any());
    verify(createApplicationUseCase, never()).execute(any());
  }

  private CreateApplicationCommand stubCreateCommand() {
    UUID id = UUID.randomUUID();
    return new CreateApplicationCommand(
        id,
        "APPLICATION_SUBMITTED",
        "LAA-123",
        Map.of("id", id.toString()),
        "{}",
        1,
        "BaseCivilApplication.json");
  }
}
