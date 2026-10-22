package uk.gov.justice.laa.dstew.access.command.application;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uk.gov.justice.laa.dstew.access.command.RetryingCommandDispatcher;
import uk.gov.justice.laa.dstew.access.command.application.data.ApplicationDraftStore;
import uk.gov.justice.laa.dstew.access.command.application.decision.MakeApplicationDecisionUseCase;
import uk.gov.justice.laa.dstew.access.command.application.document.UploadDocumentUseCase;
import uk.gov.justice.laa.dstew.access.command.application.note.CreateNoteUseCase;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.decision.MakePriorAuthorityDecisionUseCase;
import uk.gov.justice.laa.dstew.access.command.application.update.UpdateApplicationUseCase;
import uk.gov.justice.laa.dstew.access.query.SubscriptionProjectionGateway;
import uk.gov.justice.laa.dstew.access.service.sds.SdsService;
import uk.gov.justice.laa.dstew.access.utils.BaseSecuredUseCaseTest;
import uk.gov.justice.laa.dstew.access.utils.TestSecurityConfig;

@SpringBootTest(
    classes = {
      CreateApplicationUseCase.class,
      UpdateApplicationUseCase.class,
      MakeApplicationDecisionUseCase.class,
      CreateNoteUseCase.class,
      MakePriorAuthorityDecisionUseCase.class,
      UploadDocumentUseCase.class,
      TestSecurityConfig.class
    })
@ImportAutoConfiguration(
    exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
class ApplicationCommandUseCasesSecurityTest extends BaseSecuredUseCaseTest {

  @Autowired private CreateApplicationUseCase createApplicationUseCase;
  @Autowired private UpdateApplicationUseCase updateApplicationUseCase;
  @Autowired private MakeApplicationDecisionUseCase makeApplicationDecisionUseCase;
  @Autowired private CreateNoteUseCase createNoteUseCase;
  @Autowired private MakePriorAuthorityDecisionUseCase makePriorAuthorityDecisionUseCase;
  @Autowired private UploadDocumentUseCase uploadDocumentUseCase;

  @MockitoBean private RetryingCommandDispatcher dispatcher;
  @MockitoBean private SubscriptionProjectionGateway projectionGateway;
  @MockitoBean private SdsService sdsService;
  @MockitoBean private ApplicationDraftStore draftStore;

  @Test
  void givenNoRole_whenExecuteSecuredCommandUseCases_thenThrowsAuthorizationDeniedException() {
    setSecurityContext(NO_ROLE);

    assertDenied(() -> createApplicationUseCase.execute(null));
    assertDenied(() -> updateApplicationUseCase.execute(null));
    assertDenied(() -> makeApplicationDecisionUseCase.execute(null));
    assertDenied(() -> createNoteUseCase.execute(null));
    assertDenied(() -> makePriorAuthorityDecisionUseCase.execute(null));
    assertDenied(() -> uploadDocumentUseCase.execute(null, null));
    assertDenied(() -> uploadDocumentUseCase.execute(null, null, null, null));

    verifyNoInteractions(dispatcher, projectionGateway, sdsService, draftStore);
  }

  @Test
  void
      givenBlankAuthenticatedName_whenExecuteSecuredCommandUseCases_thenThrowsAuthorizationDeniedException() {
    setSecurityContextWithName(" ", CASEWORKER_ROLE);

    assertDenied(() -> createApplicationUseCase.execute(null));
    assertDenied(() -> updateApplicationUseCase.execute(null));
    assertDenied(() -> makeApplicationDecisionUseCase.execute(null));
    assertDenied(() -> createNoteUseCase.execute(null));
    assertDenied(() -> makePriorAuthorityDecisionUseCase.execute(null));
    assertDenied(() -> uploadDocumentUseCase.execute(null, null));
    assertDenied(() -> uploadDocumentUseCase.execute(null, null, null, null));

    verifyNoInteractions(dispatcher, projectionGateway, sdsService, draftStore);
  }

  private void assertDenied(ThrowingInvocation invocation) {
    assertThatExceptionOfType(AuthorizationDeniedException.class)
        .isThrownBy(invocation::invoke)
        .withMessageContaining("Access Denied");
  }

  @FunctionalInterface
  private interface ThrowingInvocation {
    void invoke();
  }
}
