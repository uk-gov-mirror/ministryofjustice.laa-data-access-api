package uk.gov.justice.laa.dstew.access.usecase.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import uk.gov.justice.laa.dstew.access.query.application.history.ApplicationHistoryResult;
import uk.gov.justice.laa.dstew.access.query.application.history.FindApplicationHistoryQuery;
import uk.gov.justice.laa.dstew.access.utils.BaseSecuredUseCaseTest;
import uk.gov.justice.laa.dstew.access.utils.TestSecurityConfig;

@SpringBootTest(classes = {ApplicationQueryUseCase.class, TestSecurityConfig.class})
@ImportAutoConfiguration(
    exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
class ApplicationQueryUseCaseSecurityTest extends BaseSecuredUseCaseTest {

  @Autowired private ApplicationQueryUseCase useCase;

  @MockitoBean private QueryGateway queryGateway;
  @MockitoBean private ApplicationReadAccessPolicyProvider accessPolicyProvider;

  @Test
  void givenNoRole_whenGetApplications_thenThrowsAuthorizationDeniedException() {
    setSecurityContext(NO_ROLE);

    assertThatExceptionOfType(AuthorizationDeniedException.class)
        .isThrownBy(
            () ->
                useCase.getApplications(
                    new FindAllApplicationsQuery(
                        null, null, null, null, null, null, null, null, 1, 20)))
        .withMessageContaining("Access Denied");

    verifyNoInteractions(queryGateway);
  }

  @Test
  void givenBlankAuthenticatedName_whenGetApplicationById_thenThrowsAuthorizationDeniedException() {
    setSecurityContextWithName(" ", CASEWORKER_ROLE);

    assertThatExceptionOfType(AuthorizationDeniedException.class)
        .isThrownBy(() -> useCase.getApplicationById(UUID.randomUUID()))
        .withMessageContaining("Access Denied");

    verifyNoInteractions(queryGateway);
  }

  @Test
  void givenCaseworkerRoleAndName_whenGetApplications_thenDelegatesToQueryGateway() {
    setSecurityContext(CASEWORKER_ROLE);

    FindAllApplicationsQuery query =
        new FindAllApplicationsQuery(null, null, null, null, null, null, null, null, 1, 20);
    FindAllApplicationsResult expected =
        new FindAllApplicationsResult(List.of(), Map.of(), Map.of(), Map.of(), 0, 1, 20);
    when(queryGateway.query(
            any(FindAllApplicationsQuery.class), eq(FindAllApplicationsResult.class)))
        .thenReturn(CompletableFuture.completedFuture(expected));

    FindAllApplicationsResult result = useCase.getApplications(query);

    assertThat(result).isSameAs(expected);
  }

  @Test
  void givenMatterTypeCode_whenGettingApplications_thenPassesExactCodeToQuery() {
    setSecurityContext(CASEWORKER_ROLE);
    FindAllApplicationsQuery query =
        new FindAllApplicationsQuery(null, null, "KPBLW", null, null, null, null, null, 1, 20);
    FindAllApplicationsResult expected =
        new FindAllApplicationsResult(List.of(), Map.of(), Map.of(), Map.of(), 0, 1, 20);
    when(queryGateway.query(
            any(FindAllApplicationsQuery.class), eq(FindAllApplicationsResult.class)))
        .thenReturn(CompletableFuture.completedFuture(expected));

    useCase.getApplications(query);

    ArgumentCaptor<FindAllApplicationsQuery> captured =
        ArgumentCaptor.forClass(FindAllApplicationsQuery.class);
    verify(queryGateway).query(captured.capture(), eq(FindAllApplicationsResult.class));
    assertThat(captured.getValue().matterTypeCode()).isEqualTo("KPBLW");
  }

  @Test
  void givenCaseworker_whenGettingApplicationDetail_thenReturnsDetail() {
    setSecurityContext(CASEWORKER_ROLE);
    UUID applicationId = UUID.randomUUID();
    ApplicationReadModel application =
        ApplicationReadModel.builder().applicationId(applicationId).build();
    ApplicationDetailResult expected =
        new ApplicationDetailResult(application, null, List.of(), Map.of());
    when(queryGateway.query(
            any(FindApplicationDetailQuery.class), eq(ApplicationDetailResult.class)))
        .thenReturn(CompletableFuture.completedFuture(expected));

    assertThat(useCase.getApplicationDetail(applicationId)).isSameAs(expected);
  }

  @Test
  void givenMissingApplication_whenGettingApplicationDetail_thenThrowsNotFound() {
    setSecurityContext(CASEWORKER_ROLE);
    UUID applicationId = UUID.randomUUID();
    when(queryGateway.query(
            any(FindApplicationDetailQuery.class), eq(ApplicationDetailResult.class)))
        .thenReturn(CompletableFuture.completedFuture(null));

    assertThatExceptionOfType(ResourceNotFoundException.class)
        .isThrownBy(() -> useCase.getApplicationDetail(applicationId))
        .withMessageContaining("No application found with ID: " + applicationId);
  }

  @Test
  void givenApplicationMissing_whenGetApplicationById_thenThrowsResourceNotFoundException() {
    setSecurityContext(CASEWORKER_ROLE);
    UUID applicationId = UUID.randomUUID();
    when(queryGateway.query(any(FindApplicationByIdQuery.class), eq(ApplicationReadModel.class)))
        .thenReturn(CompletableFuture.completedFuture(null));

    assertThatExceptionOfType(ResourceNotFoundException.class)
        .isThrownBy(() -> useCase.getApplicationById(applicationId))
        .withMessageContaining("No application found with ID: " + applicationId);
  }

  @Test
  void givenCertificateMissing_whenGetCertificate_thenThrowsResourceNotFoundException() {
    setSecurityContext(CASEWORKER_ROLE);
    UUID applicationId = UUID.randomUUID();
    ApplicationReadModel application =
        ApplicationReadModel.builder().applicationId(applicationId).build();

    when(queryGateway.query(any(FindApplicationByIdQuery.class), eq(ApplicationReadModel.class)))
        .thenReturn(CompletableFuture.completedFuture(application));

    assertThatExceptionOfType(ResourceNotFoundException.class)
        .isThrownBy(() -> useCase.getCertificate(applicationId))
        .withMessageContaining("No certificate found for application id: " + applicationId);
  }

  @Test
  void givenCertificatePresent_whenGetCertificate_thenReturnsCertificate() {
    setSecurityContext(CASEWORKER_ROLE);
    UUID applicationId = UUID.randomUUID();
    Map<String, Object> certificate = Map.of("status", "granted");
    ApplicationReadModel application =
        ApplicationReadModel.builder()
            .applicationId(applicationId)
            .certificate(certificate)
            .build();

    when(queryGateway.query(any(FindApplicationByIdQuery.class), eq(ApplicationReadModel.class)))
        .thenReturn(CompletableFuture.completedFuture(application));

    assertThat(useCase.getCertificate(applicationId)).isEqualTo(certificate);
  }

  @Test
  void givenCaseworkerRoleAndName_whenGetApplicationHistory_thenDelegatesToQueryGateway() {
    setSecurityContext(CASEWORKER_ROLE);
    UUID applicationId = UUID.randomUUID();
    List<String> requestedTypes = List.of("APPLICATION_CREATED");
    ApplicationHistoryResult expected = new ApplicationHistoryResult(List.of(), List.of());

    when(queryGateway.query(
            any(FindApplicationHistoryQuery.class), eq(ApplicationHistoryResult.class)))
        .thenReturn(CompletableFuture.completedFuture(expected));

    assertThat(useCase.getApplicationHistory(applicationId, requestedTypes)).isSameAs(expected);
  }

  @Test
  void givenNotesMissing_whenGetNotesForApplication_thenThrowsResourceNotFoundException() {
    setSecurityContext(CASEWORKER_ROLE);
    UUID applicationId = UUID.randomUUID();

    when(queryGateway.query(
            any(FindNotesForApplicationQuery.class), eq(ApplicationNotesResult.class)))
        .thenReturn(CompletableFuture.completedFuture(null));

    assertThatExceptionOfType(ResourceNotFoundException.class)
        .isThrownBy(() -> useCase.getNotesForApplication(applicationId))
        .withMessageContaining("No application found with ID: " + applicationId);
  }

  @Test
  void givenNotesPresent_whenGetNotesForApplication_thenReturnsNotes() {
    setSecurityContext(CASEWORKER_ROLE);
    UUID applicationId = UUID.randomUUID();
    ApplicationNotesResult expected = new ApplicationNotesResult(List.of());

    when(queryGateway.query(
            any(FindNotesForApplicationQuery.class), eq(ApplicationNotesResult.class)))
        .thenReturn(CompletableFuture.completedFuture(expected));

    assertThat(useCase.getNotesForApplication(applicationId)).isSameAs(expected);
  }
}
