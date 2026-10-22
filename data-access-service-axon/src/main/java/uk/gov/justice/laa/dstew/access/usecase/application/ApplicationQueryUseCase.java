package uk.gov.justice.laa.dstew.access.usecase.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.springframework.stereotype.Service;
import uk.gov.justice.laa.dstew.access.exception.ResourceNotFoundException;
import uk.gov.justice.laa.dstew.access.query.application.ApplicationDetailResult;
import uk.gov.justice.laa.dstew.access.query.application.ApplicationNotesResult;
import uk.gov.justice.laa.dstew.access.query.application.ApplicationReadAccessPolicyProvider;
import uk.gov.justice.laa.dstew.access.query.application.ApplicationReadModel;
import uk.gov.justice.laa.dstew.access.query.application.FindAllApplicationsQuery;
import uk.gov.justice.laa.dstew.access.query.application.FindAllApplicationsResult;
import uk.gov.justice.laa.dstew.access.query.application.FindApplicationByIdQuery;
import uk.gov.justice.laa.dstew.access.query.application.FindApplicationDetailQuery;
import uk.gov.justice.laa.dstew.access.query.application.FindNotesForApplicationQuery;
import uk.gov.justice.laa.dstew.access.query.application.history.ApplicationHistoryIntegrityException;
import uk.gov.justice.laa.dstew.access.query.application.history.ApplicationHistoryResult;
import uk.gov.justice.laa.dstew.access.query.application.history.FindApplicationHistoryQuery;
import uk.gov.justice.laa.dstew.access.security.AllowApiCaseworker;

/** Secured use case for Application read operations. */
@Service
public class ApplicationQueryUseCase {

  private final QueryGateway queryGateway;
  private final ApplicationReadAccessPolicyProvider accessPolicyProvider;

  public ApplicationQueryUseCase(
      QueryGateway queryGateway, ApplicationReadAccessPolicyProvider accessPolicyProvider) {
    this.queryGateway = queryGateway;
    this.accessPolicyProvider = accessPolicyProvider;
  }

  /** Returns a page of application summaries. */
  @AllowApiCaseworker
  public FindAllApplicationsResult getApplications(FindAllApplicationsQuery query) {
    FindAllApplicationsQuery scopedQuery =
        new FindAllApplicationsQuery(
            query.status(),
            query.laaReference(),
            query.matterTypeCode(),
            query.clientFirstName(),
            query.clientLastName(),
            query.clientDateOfBirth(),
            query.autoGranted(),
            query.sortBy(),
            query.orderBy(),
            query.page(),
            query.pageSize(),
            accessPolicyProvider.resolve());
    return queryGateway.query(scopedQuery, FindAllApplicationsResult.class).join();
  }

  /** Returns the requested application or throws when absent. */
  @AllowApiCaseworker
  public ApplicationReadModel getApplicationById(UUID id) {
    ApplicationReadModel application =
        queryGateway
            .query(
                new FindApplicationByIdQuery(id, accessPolicyProvider.resolve()),
                ApplicationReadModel.class)
            .join();
    if (application == null) {
      throw new ResourceNotFoundException("No application found with ID: " + id);
    }
    return application;
  }

  /**
   * Returns application detail using record visibility after starter-managed HTTP authorization.
   */
  public ApplicationDetailResult getApplicationDetail(UUID id) {
    ApplicationDetailResult detail =
        queryGateway
            .query(
                new FindApplicationDetailQuery(id, accessPolicyProvider.resolve()),
                ApplicationDetailResult.class)
            .join();
    if (detail == null) {
      throw new ResourceNotFoundException("No application found with ID: " + id);
    }
    return detail;
  }

  /** Returns the stored certificate for an application. */
  @AllowApiCaseworker
  public Map<String, Object> getCertificate(UUID id) {
    ApplicationReadModel application = getApplicationById(id);
    if (application.getCertificate() == null) {
      throw new ResourceNotFoundException("No certificate found for application id: " + id);
    }
    return application.getCertificate();
  }

  /** Returns domain-event history for an application. */
  @AllowApiCaseworker
  public ApplicationHistoryResult getApplicationHistory(
      UUID applicationId, List<String> requestedTypes) {
    accessPolicyProvider.resolve();
    try {
      return queryGateway
          .query(
              new FindApplicationHistoryQuery(applicationId, requestedTypes),
              ApplicationHistoryResult.class)
          .join();
    } catch (CompletionException completionException) {
      ApplicationHistoryIntegrityException integrityException =
          findIntegrityException(completionException);
      if (integrityException != null) {
        throw integrityException;
      }
      throw completionException;
    }
  }

  private static ApplicationHistoryIntegrityException findIntegrityException(Throwable throwable) {
    Throwable cause = throwable.getCause();
    while (cause != null) {
      if (cause instanceof ApplicationHistoryIntegrityException integrityException) {
        return integrityException;
      }
      cause = cause.getCause();
    }
    return null;
  }

  /** Returns notes for an application or throws when the application is absent. */
  @AllowApiCaseworker
  public ApplicationNotesResult getNotesForApplication(UUID id) {
    ApplicationNotesResult result =
        queryGateway
            .query(
                new FindNotesForApplicationQuery(id, accessPolicyProvider.resolve()),
                ApplicationNotesResult.class)
            .join();
    if (result == null) {
      throw new ResourceNotFoundException("No application found with ID: " + id);
    }
    return result;
  }
}
