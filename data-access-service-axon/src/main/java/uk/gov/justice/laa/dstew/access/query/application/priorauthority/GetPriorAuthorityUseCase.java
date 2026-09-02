package uk.gov.justice.laa.dstew.access.query.application.priorauthority;

import java.util.UUID;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.springframework.stereotype.Service;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.data.PriorAuthorityDataPayload;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.data.PriorAuthorityDataStore;
import uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityResult;
import uk.gov.justice.laa.dstew.access.exception.ResourceNotFoundException;

/** Retrieves and hydrates the current Prior Authority submission. */
@Service
public class GetPriorAuthorityUseCase {

  private final PriorAuthorityDataStore priorAuthorityDataStore;
  private final QueryGateway queryGateway;

  public GetPriorAuthorityUseCase(
      PriorAuthorityDataStore priorAuthorityDataStore, QueryGateway queryGateway) {
    this.priorAuthorityDataStore = priorAuthorityDataStore;
    this.queryGateway = queryGateway;
  }

  /** Retrieves the Prior Authority identified by its submission ID. */
  public PriorAuthorityResult getPriorAuthority(UUID priorAuthorityId) {
    PriorAuthorityReadModel priorAuthority =
        queryGateway
            .query(
                new FindPriorAuthorityBySubmissionIdQuery(priorAuthorityId),
                PriorAuthorityReadModel.class)
            .join();
    if (priorAuthority == null) {
      throw new ResourceNotFoundException("No prior authority found with ID: " + priorAuthorityId);
    }

    PriorAuthorityDataPayload payload =
        priorAuthorityDataStore.get(priorAuthorityId, priorAuthority.getDataVersion());
    return PriorAuthorityResult.from(priorAuthority, payload.content());
  }
}
