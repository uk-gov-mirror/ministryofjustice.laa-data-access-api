package uk.gov.justice.laa.dstew.access.command.application.priorauthority.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityContent;
import uk.gov.justice.laa.dstew.access.util.PayloadFingerprint;

class PriorAuthorityDataStoreTest {

  private PriorAuthorityDataRepository repository;
  private PriorAuthorityDataStore store;

  @BeforeEach
  void setUp() {
    repository = mock(PriorAuthorityDataRepository.class);
    store = new PriorAuthorityDataStore(repository);
  }

  @Test
  void
      givenPayload_whenAppended_thenStoresExactIdApplicationIdPayloadHashAndTimestampAndReturns64CharFingerprint() {
    UUID submissionId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    PriorAuthorityContent content =
        new PriorAuthorityContent("EXPERT", null, null, null, null, List.of());
    Instant occurredAt = Instant.parse("2026-08-01T10:00:00Z");
    PriorAuthorityDataPayload payload =
        new PriorAuthorityDataPayload(
            submissionId, applicationId, content, "request-json", occurredAt);

    String hash =
        store.append(submissionId, 0L, applicationId, payload, "request-json", occurredAt);

    ArgumentCaptor<PriorAuthorityData> captor = ArgumentCaptor.forClass(PriorAuthorityData.class);
    verify(repository).saveAndFlush(captor.capture());
    assertThat(hash).isEqualTo(PayloadFingerprint.compute("request-json")).hasSize(64);
    assertThat(captor.getValue().getId()).isEqualTo(new PriorAuthorityDataId(submissionId, 0L));
    assertThat(captor.getValue().getApplicationId()).isEqualTo(applicationId);
    assertThat(captor.getValue().getPayload()).isEqualTo(payload);
    assertThat(captor.getValue().getPayloadHash()).isEqualTo(hash);
    assertThat(captor.getValue().getCreatedAt()).isEqualTo(occurredAt);
  }

  @Test
  void givenDuplicateVersion_whenRepositoryRejectsInsert_thenFailurePropagates() {
    when(repository.saveAndFlush(any()))
        .thenThrow(new DataIntegrityViolationException("duplicate prior authority data version"));

    assertThatThrownBy(
            () ->
                store.append(
                    UUID.randomUUID(),
                    1L,
                    UUID.randomUUID(),
                    new PriorAuthorityDataPayload(
                        UUID.randomUUID(), UUID.randomUUID(), null, "req", Instant.now()),
                    "req",
                    Instant.now()))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("duplicate prior authority data version");
  }

  @Test
  void givenKnownInput_whenComputeFingerprint_thenReturnsDeterministicSha256() {
    assertThat(PayloadFingerprint.compute("abc"))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @Test
  void givenStoredVersion_whenGet_thenReturnsPriorAuthorityDataPayload() {
    UUID submissionId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    PriorAuthorityDataPayload expectedPayload =
        new PriorAuthorityDataPayload(submissionId, applicationId, null, "req", Instant.now());
    PriorAuthorityDataId id = new PriorAuthorityDataId(submissionId, 2L);
    when(repository.findById(id))
        .thenReturn(
            Optional.of(
                PriorAuthorityData.builder()
                    .id(id)
                    .applicationId(applicationId)
                    .payload(expectedPayload)
                    .build()));

    PriorAuthorityDataPayload result = store.get(submissionId, 2L);

    assertThat(result).isEqualTo(expectedPayload);
  }

  @Test
  void givenMissingVersion_whenGet_thenThrowsExactIllegalStateException() {
    UUID submissionId = UUID.randomUUID();
    when(repository.findById(new PriorAuthorityDataId(submissionId, 7L)))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> store.get(submissionId, 7L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Prior authority data not found for submission " + submissionId + " version 7");
  }
}
