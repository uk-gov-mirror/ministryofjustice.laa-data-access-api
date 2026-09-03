package uk.gov.justice.laa.dstew.access.controller.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.AttachPriorAuthorityDocumentUseCase;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.SavePriorAuthorityDraftCommand;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.SavePriorAuthorityDraftUseCase;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.SubmitPriorAuthorityDraftCommand;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.SubmitPriorAuthorityDraftUseCase;
import uk.gov.justice.laa.dstew.access.model.AttachPriorAuthorityDocumentResponse;
import uk.gov.justice.laa.dstew.access.model.SavePriorAuthorityDraftRequest;
import uk.gov.justice.laa.dstew.access.model.SavePriorAuthorityDraftResponse;
import uk.gov.justice.laa.dstew.access.model.SubmitPriorAuthorityDraftResponse;

/** Verifies that each controller endpoint delegates to the appropriate use case. */
class PriorAuthorityDraftCommandControllerTest {

  private SavePriorAuthorityDraftUseCase saveUseCase;
  private SubmitPriorAuthorityDraftUseCase submitUseCase;
  private AttachPriorAuthorityDocumentUseCase attachUseCase;
  private SavePriorAuthorityDraftCommandMapper commandMapper;
  private PriorAuthorityDraftCommandController controller;

  @BeforeEach
  void setUp() {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    saveUseCase = mock(SavePriorAuthorityDraftUseCase.class);
    submitUseCase = mock(SubmitPriorAuthorityDraftUseCase.class);
    attachUseCase = mock(AttachPriorAuthorityDocumentUseCase.class);
    commandMapper = mock(SavePriorAuthorityDraftCommandMapper.class);
    controller =
        new PriorAuthorityDraftCommandController(
            saveUseCase, submitUseCase, attachUseCase, commandMapper);
  }

  @AfterEach
  void tearDown() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void givenProjectedResult_whenSavePriorAuthorityDraft_thenReturnsCreatedResponse() {
    UUID applicationId = UUID.randomUUID();
    UUID submissionId = UUID.randomUUID();
    SavePriorAuthorityDraftRequest request = new SavePriorAuthorityDraftRequest();
    SavePriorAuthorityDraftCommand command =
        new SavePriorAuthorityDraftCommand(
            submissionId, applicationId, null, "{}", 1, "PriorAuthority.json", Instant.now());
    when(commandMapper.toCreateCommand(applicationId, request)).thenReturn(command);
    when(saveUseCase.create(command)).thenReturn(true);

    ResponseEntity<SavePriorAuthorityDraftResponse> response =
        controller.savePriorAuthorityDraft(null, applicationId, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getSubmissionId()).isEqualTo(submissionId);
    assertThat(response.getHeaders().getLocation()).isNotNull();
    assertThat(response.getHeaders().getLocation().toString()).endsWith("/" + submissionId);
    verify(saveUseCase).create(command);
  }

  @Test
  void givenTimeoutResult_whenSavePriorAuthorityDraft_thenReturnsAcceptedResponse() {
    UUID applicationId = UUID.randomUUID();
    UUID submissionId = UUID.randomUUID();
    SavePriorAuthorityDraftRequest request = new SavePriorAuthorityDraftRequest();
    SavePriorAuthorityDraftCommand command =
        new SavePriorAuthorityDraftCommand(
            submissionId, applicationId, null, "{}", 1, "PriorAuthority.json", Instant.now());
    when(commandMapper.toCreateCommand(applicationId, request)).thenReturn(command);
    when(saveUseCase.create(command)).thenReturn(false);

    ResponseEntity<SavePriorAuthorityDraftResponse> response =
        controller.savePriorAuthorityDraft(null, applicationId, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    verify(saveUseCase).create(command);
  }

  @Test
  void givenRequest_whenUpdatePriorAuthorityDraft_thenDelegatesToUseCaseAndReturnsNoContent() {
    UUID submissionId = UUID.randomUUID();
    SavePriorAuthorityDraftRequest request = new SavePriorAuthorityDraftRequest();
    SavePriorAuthorityDraftCommand command =
        new SavePriorAuthorityDraftCommand(
            submissionId, null, null, "{}", 1, "PriorAuthority.json", Instant.now());
    when(commandMapper.toUpdateCommand(submissionId, request)).thenReturn(command);

    ResponseEntity<Void> response =
        controller.updatePriorAuthorityDraft(null, submissionId, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    verify(saveUseCase).update(command);
  }

  @Test
  void givenProjectionConfirmed_whenSubmitPriorAuthorityDraft_thenReturnsCreatedResponse() {
    UUID submissionId = UUID.randomUUID();
    when(submitUseCase.submit(new SubmitPriorAuthorityDraftCommand(submissionId, Instant.now())))
        .thenReturn(true);
    when(submitUseCase.submit(org.mockito.ArgumentMatchers.any())).thenReturn(true);

    ResponseEntity<SubmitPriorAuthorityDraftResponse> response =
        controller.submitPriorAuthorityDraft(null, submissionId);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getSubmissionId()).isEqualTo(submissionId);
    assertThat(response.getHeaders().getLocation()).isNotNull();
    assertThat(response.getHeaders().getLocation().toString()).endsWith("/" + submissionId);
  }

  @Test
  void givenProjectionTimeout_whenSubmitPriorAuthorityDraft_thenReturnsAcceptedResponse() {
    UUID submissionId = UUID.randomUUID();
    when(submitUseCase.submit(org.mockito.ArgumentMatchers.any())).thenReturn(false);

    ResponseEntity<SubmitPriorAuthorityDraftResponse> response =
        controller.submitPriorAuthorityDraft(null, submissionId);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
  }

  @Test
  void givenValidFile_whenAttachPriorAuthorityDocument_thenDelegatesToUseCaseAndReturnsCreated() {
    UUID priorAuthorityId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    MultipartFile file =
        new MockMultipartFile("file", "evidence.pdf", "application/pdf", "content".getBytes());
    when(attachUseCase.attach(
            org.mockito.ArgumentMatchers.eq(priorAuthorityId),
            org.mockito.ArgumentMatchers.eq(file),
            org.mockito.ArgumentMatchers.any(Instant.class)))
        .thenReturn(documentId);

    ResponseEntity<AttachPriorAuthorityDocumentResponse> response =
        controller.attachPriorAuthorityDocument(null, priorAuthorityId, file);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getDocumentId()).isEqualTo(documentId);
    verify(attachUseCase)
        .attach(
            org.mockito.ArgumentMatchers.eq(priorAuthorityId),
            org.mockito.ArgumentMatchers.eq(file),
            org.mockito.ArgumentMatchers.any(Instant.class));
  }
}
