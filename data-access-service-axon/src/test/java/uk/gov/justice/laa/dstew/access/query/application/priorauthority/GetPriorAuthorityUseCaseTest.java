package uk.gov.justice.laa.dstew.access.query.application.priorauthority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityResult;
import uk.gov.justice.laa.dstew.access.exception.ResourceNotFoundException;

class GetPriorAuthorityUseCaseTest {

  private QueryGateway queryGateway;
  private GetPriorAuthorityUseCase useCase;

  @BeforeEach
  void setUp() {
    queryGateway = mock(QueryGateway.class);
    useCase = new GetPriorAuthorityUseCase(queryGateway);
  }

  @Test
  void givenPriorAuthorityResult_whenRetrieved_thenReturnsQueryResult() {
    UUID submissionId = UUID.randomUUID();
    PriorAuthorityResult expected =
        new PriorAuthorityResult(
            submissionId, UUID.randomUUID(), "Required", "PENDING", null, null, null, null);
    when(queryGateway.query(
            eq(new FindPriorAuthorityBySubmissionIdQuery(submissionId)),
            eq(PriorAuthorityResult.class)))
        .thenReturn(CompletableFuture.completedFuture(expected));

    PriorAuthorityResult result = useCase.getPriorAuthority(submissionId);

    assertThat(result).isSameAs(expected);
  }

  @Test
  void givenQueryReportsMissingPriorAuthority_whenRetrieved_thenThrowsNotFound() {
    UUID submissionId = UUID.randomUUID();
    ResourceNotFoundException failure =
        new ResourceNotFoundException("No prior authority found with ID: " + submissionId);
    when(queryGateway.query(
            eq(new FindPriorAuthorityBySubmissionIdQuery(submissionId)),
            eq(PriorAuthorityResult.class)))
        .thenReturn(CompletableFuture.failedFuture(failure));

    assertThatExceptionOfType(ResourceNotFoundException.class)
        .isThrownBy(() -> useCase.getPriorAuthority(submissionId))
        .isSameAs(failure);
  }
}
