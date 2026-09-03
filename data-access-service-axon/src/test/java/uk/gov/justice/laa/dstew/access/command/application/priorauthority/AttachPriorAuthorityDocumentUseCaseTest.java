package uk.gov.justice.laa.dstew.access.command.application.priorauthority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import uk.gov.justice.laa.dstew.access.command.RetryingCommandDispatcher;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.data.PriorAuthorityDataPayload;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.data.PriorAuthorityDraftStore;
import uk.gov.justice.laa.dstew.access.exception.PriorAuthorityNotInProgressException;
import uk.gov.justice.laa.dstew.access.model.DocumentUploadResponse;
import uk.gov.justice.laa.dstew.access.service.sds.SdsService;

class AttachPriorAuthorityDocumentUseCaseTest {

  private RetryingCommandDispatcher dispatcher;
  private PriorAuthorityDraftStore draftStore;
  private SdsService sdsService;
  private AttachPriorAuthorityDocumentUseCase useCase;

  @BeforeEach
  void setUp() {
    dispatcher = mock(RetryingCommandDispatcher.class);
    draftStore = mock(PriorAuthorityDraftStore.class);
    sdsService = mock(SdsService.class);
    useCase = new AttachPriorAuthorityDocumentUseCase(dispatcher, draftStore, sdsService);
  }

  @Test
  void givenNoDraftInProgress_whenAttach_thenThrowsAndNeverCallsSdsOrDispatches() {
    UUID priorAuthorityId = UUID.randomUUID();
    MultipartFile file =
        new MockMultipartFile("file", "evidence.pdf", "application/pdf", "content".getBytes());
    when(draftStore.find(priorAuthorityId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> useCase.attach(priorAuthorityId, file, Instant.now()))
        .isInstanceOf(PriorAuthorityNotInProgressException.class);

    verify(sdsService, never()).saveFile(any(), any(), any());
    verify(dispatcher, never()).dispatch(any());
  }

  @Test
  void givenDraftInProgress_whenAttach_thenUploadsToSdsAndDispatchesCommandInOrder() {
    UUID priorAuthorityId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    Instant occurredAt = Instant.now();
    MultipartFile file =
        new MockMultipartFile("file", "evidence.pdf", "application/pdf", "content".getBytes());
    PriorAuthorityDataPayload draft =
        new PriorAuthorityDataPayload(priorAuthorityId, applicationId, null, "{}", occurredAt);
    DocumentUploadResponse sdsResponse = mock(DocumentUploadResponse.class);
    when(sdsResponse.getChecksum()).thenReturn("checksum-123");

    when(draftStore.find(priorAuthorityId)).thenReturn(Optional.of(draft));
    when(sdsService.saveFile(any(), any(), any())).thenReturn(sdsResponse);

    UUID documentId = useCase.attach(priorAuthorityId, file, occurredAt);

    assertThat(documentId).isNotNull();

    InOrder order = Mockito.inOrder(draftStore, dispatcher, sdsService);
    order.verify(draftStore).find(priorAuthorityId);
    order.verify(dispatcher).dispatch(new ValidateApplicationGrantedCommand(applicationId));
    order.verify(sdsService).saveFile(priorAuthorityId, documentId, file);
    order
        .verify(dispatcher)
        .dispatch(
            new AttachPriorAuthorityDocumentCommand(
                priorAuthorityId,
                documentId,
                "evidence.pdf",
                file.getSize(),
                "pdf",
                "application/pdf",
                "checksum-123",
                occurredAt));
  }

  @Test
  void givenValidationFails_whenAttach_thenPropagatesAndNeverCallsSds() {
    UUID priorAuthorityId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    MultipartFile file =
        new MockMultipartFile("file", "evidence.pdf", "application/pdf", "content".getBytes());
    PriorAuthorityDataPayload draft =
        new PriorAuthorityDataPayload(priorAuthorityId, applicationId, null, "{}", Instant.now());
    RuntimeException failure = new RuntimeException("Application must be granted");

    when(draftStore.find(priorAuthorityId)).thenReturn(Optional.of(draft));
    Mockito.doThrow(failure)
        .when(dispatcher)
        .dispatch(new ValidateApplicationGrantedCommand(applicationId));

    assertThatThrownBy(() -> useCase.attach(priorAuthorityId, file, Instant.now()))
        .isSameAs(failure);

    verify(sdsService, never()).saveFile(any(), any(), any());
  }
}
