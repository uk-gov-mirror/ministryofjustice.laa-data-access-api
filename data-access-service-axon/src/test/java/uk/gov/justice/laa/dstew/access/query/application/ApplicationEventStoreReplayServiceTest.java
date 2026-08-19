package uk.gov.justice.laa.dstew.access.query.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gov.justice.laa.dstew.access.applicationcontent.ApplicationProvider;
import uk.gov.justice.laa.dstew.access.command.application.ApplicationCreatedEvent;
import uk.gov.justice.laa.dstew.access.command.application.ApplicationLinkedEvent;
import uk.gov.justice.laa.dstew.access.command.application.AutoGrantedState;
import uk.gov.justice.laa.dstew.access.command.application.assignment.ApplicationAssignedToCaseworkerEvent;
import uk.gov.justice.laa.dstew.access.command.application.assignment.ApplicationUnassignedFromCaseworkerEvent;
import uk.gov.justice.laa.dstew.access.command.application.data.ApplicationDataPayload;
import uk.gov.justice.laa.dstew.access.command.application.data.ApplicationDataStore;
import uk.gov.justice.laa.dstew.access.command.application.decision.ApplicationDecisionMadeEvent;
import uk.gov.justice.laa.dstew.access.command.application.linkedgroup.LinkedApplicationGroupRequested;
import uk.gov.justice.laa.dstew.access.command.application.note.NoteCreatedEvent;
import uk.gov.justice.laa.dstew.access.command.application.ready.ApplicationReadyForManualAssessmentEvent;
import uk.gov.justice.laa.dstew.access.command.application.update.ApplicationUpdatedEvent;

class ApplicationEventStoreReplayServiceTest {

  private final EventStore eventStore = mock(EventStore.class);
  private final ApplicationDataStore applicationDataStore = mock(ApplicationDataStore.class);

  private ApplicationEventStoreReplayService service;

  @BeforeEach
  void setUp() {
    service = new ApplicationEventStoreReplayService(eventStore, applicationDataStore);
  }

  @Test
  void replaysAvailableEventsAndHydratesTheResult() {
    UUID applicationId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-08-19T10:15:30Z");
    ApplicationCreatedEvent event = applicationCreatedEvent(applicationId, 1L, occurredAt);
    ApplicationDataPayload data = applicationData("LAA-987");
    when(eventStore.open(any(), isNull()))
        .thenReturn(MessageStream.<EventMessage>fromItems(eventMessage(event)));
    when(applicationDataStore.get(applicationId, 1L)).thenReturn(data);

    var result = service.replay(applicationId);

    assertThat(result)
        .hasValueSatisfying(
            model -> {
              assertThat(model.getApplicationId()).isEqualTo(applicationId);
              assertThat(model.getStatus()).isEqualTo("APPLICATION_SUBMITTED");
              assertThat(model.getApplicationDataVersion()).isEqualTo(1L);
              assertThat(model.getApplicationVersion()).isZero();
              assertThat(model.getSchemaVersion()).isEqualTo(2);
              assertThat(model.getCreatedAt()).isEqualTo(occurredAt);
              assertThat(model.getModifiedAt()).isEqualTo(occurredAt);
              assertThat(model.getLaaReference()).isEqualTo("LAA-987");
              assertThat(model.getProvider().getOfficeCode()).isEqualTo("1A001B");
              assertThat(model.getAutoGranted()).isEqualTo(AutoGrantedState.PENDING);
            });
    verify(applicationDataStore).get(applicationId, 1L);
  }

  @Test
  void replaysAllSupportedEventsInStreamOrder() {
    UUID applicationId = UUID.randomUUID();
    UUID assignedCaseworkerId = UUID.randomUUID();
    UUID linkedLeadApplicationId = UUID.randomUUID();
    Instant createdAt = Instant.parse("2026-08-19T10:00:00Z");
    Instant linkedAt = Instant.parse("2026-08-19T10:07:00Z");
    ApplicationCreatedEvent created = applicationCreatedEvent(applicationId, 1L, createdAt);
    ApplicationUpdatedEvent updated = applicationUpdatedEvent(applicationId, 1L, 2L);
    ApplicationDecisionMadeEvent decision = applicationDecisionMadeEvent(applicationId, 2L, 3L);
    ApplicationAssignedToCaseworkerEvent assigned =
        applicationAssignedToCaseworkerEvent(applicationId, 3L, 4L, assignedCaseworkerId);
    ApplicationUnassignedFromCaseworkerEvent unassigned =
        applicationUnassignedFromCaseworkerEvent(applicationId, 4L, 5L);
    ApplicationReadyForManualAssessmentEvent manualAssessment =
        applicationReadyForManualAssessmentEvent(applicationId, 5L, 6L);
    NoteCreatedEvent note = noteCreatedEvent(applicationId, 7L);
    ApplicationLinkedEvent linked =
        applicationLinkedEvent(applicationId, linkedLeadApplicationId, linkedAt);
    LinkedApplicationGroupRequested groupRequested =
        linkedApplicationGroupRequested(applicationId, linkedLeadApplicationId);
    when(eventStore.open(any(), isNull()))
        .thenReturn(
            MessageStream.fromItems(
                eventMessage(created, createdAt),
                eventMessage(updated, updated.occurredAt()),
                eventMessage(decision, decision.occurredAt()),
                eventMessage(assigned, assigned.occurredAt()),
                eventMessage(unassigned, unassigned.occurredAt()),
                eventMessage(manualAssessment, manualAssessment.occurredAt()),
                eventMessage(note, note.occurredAt()),
                eventMessage(linked, linkedAt),
                eventMessage(groupRequested, groupRequested.occurredAt())));
    when(applicationDataStore.get(applicationId, 7L)).thenReturn(applicationData("LAA-987"));

    var result = service.replay(applicationId);

    assertThat(result)
        .hasValueSatisfying(
            model -> {
              assertThat(model.getStatus()).isEqualTo("APPLICATION_SUBMITTED");
              assertThat(model.getApplicationVersion()).isEqualTo(5L);
              assertThat(model.getApplicationDataVersion()).isEqualTo(7L);
              assertThat(model.getCaseworkerId()).isNull();
              assertThat(model.getLeadApplicationId()).isEqualTo(linkedLeadApplicationId);
              assertThat(model.getModifiedAt()).isEqualTo(linkedAt);
            });
    verify(applicationDataStore).get(applicationId, 7L);
  }

  @Test
  void returnsEmptyWhenNoEntriesAreImmediatelyAvailable() {
    UUID applicationId = UUID.randomUUID();
    when(eventStore.open(any(), isNull())).thenReturn(MessageStream.empty());

    assertThat(service.replay(applicationId)).isEmpty();
  }

  @Test
  void returnsEmptyWhenTheReplayedDataVersionCannotBeLoaded() {
    UUID applicationId = UUID.randomUUID();
    ApplicationCreatedEvent event =
        applicationCreatedEvent(applicationId, 4L, Instant.parse("2026-08-19T10:15:30Z"));
    when(eventStore.open(any(), isNull()))
        .thenReturn(MessageStream.<EventMessage>fromItems(eventMessage(event)));
    when(applicationDataStore.get(applicationId, 4L))
        .thenThrow(new IllegalStateException("Missing"));

    assertThat(service.replay(applicationId)).isEmpty();
  }

  @Test
  void wrapsStreamErrorsWithTheApplicationIdentifier() {
    UUID applicationId = UUID.randomUUID();
    RuntimeException streamFailure = new RuntimeException("Event store unavailable");
    when(eventStore.open(any(), isNull())).thenReturn(MessageStream.failed(streamFailure));

    assertThatThrownBy(() -> service.replay(applicationId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Unable to replay event stream for application " + applicationId)
        .hasCause(streamFailure);
  }

  private static ApplicationCreatedEvent applicationCreatedEvent(
      UUID applicationId, long applicationDataVersion, Instant occurredAt) {
    return new ApplicationCreatedEvent(
        applicationId,
        applicationDataVersion,
        "fingerprint",
        "APPLICATION_SUBMITTED",
        2,
        occurredAt,
        null,
        List.of());
  }

  private static ApplicationUpdatedEvent applicationUpdatedEvent(
      UUID applicationId, long applicationVersion, long applicationDataVersion) {
    return new ApplicationUpdatedEvent(
        applicationId,
        applicationVersion,
        applicationDataVersion,
        "APPLICATION_DRAFT",
        "APPLICATION_SUBMITTED",
        Instant.parse("2026-08-19T10:01:00Z"));
  }

  private static ApplicationDecisionMadeEvent applicationDecisionMadeEvent(
      UUID applicationId, long applicationVersion, long applicationDataVersion) {
    return new ApplicationDecisionMadeEvent(
        applicationId,
        applicationVersion,
        applicationDataVersion,
        "GRANTED",
        AutoGrantedState.AUTOGRANTED,
        Instant.parse("2026-08-19T10:02:00Z"));
  }

  private static ApplicationAssignedToCaseworkerEvent applicationAssignedToCaseworkerEvent(
      UUID applicationId, long applicationVersion, long applicationDataVersion, UUID caseworkerId) {
    return new ApplicationAssignedToCaseworkerEvent(
        applicationId,
        applicationVersion,
        applicationDataVersion,
        caseworkerId,
        Instant.parse("2026-08-19T10:03:00Z"));
  }

  private static ApplicationUnassignedFromCaseworkerEvent applicationUnassignedFromCaseworkerEvent(
      UUID applicationId, long applicationVersion, long applicationDataVersion) {
    return new ApplicationUnassignedFromCaseworkerEvent(
        applicationId,
        applicationVersion,
        applicationDataVersion,
        Instant.parse("2026-08-19T10:04:00Z"));
  }

  private static ApplicationReadyForManualAssessmentEvent applicationReadyForManualAssessmentEvent(
      UUID applicationId, long applicationVersion, long applicationDataVersion) {
    return new ApplicationReadyForManualAssessmentEvent(
        applicationId,
        applicationVersion,
        applicationDataVersion,
        Instant.parse("2026-08-19T10:05:00Z"));
  }

  private static NoteCreatedEvent noteCreatedEvent(
      UUID applicationId, long applicationDataVersion) {
    return new NoteCreatedEvent(
        applicationId, applicationDataVersion, Instant.parse("2026-08-19T10:06:00Z"));
  }

  private static ApplicationLinkedEvent applicationLinkedEvent(
      UUID applicationId, UUID leadApplicationId, Instant occurredAt) {
    return new ApplicationLinkedEvent(applicationId, leadApplicationId, occurredAt);
  }

  private static LinkedApplicationGroupRequested linkedApplicationGroupRequested(
      UUID applicationId, UUID leadApplicationId) {
    return new LinkedApplicationGroupRequested(
        applicationId,
        leadApplicationId,
        List.of(applicationId),
        Instant.parse("2026-08-19T10:08:00Z"));
  }

  private EventMessage eventMessage(Object event, Instant occurredAt) {
    return new GenericEventMessage(
        UUID.randomUUID().toString(),
        new MessageType(event.getClass()),
        event,
        Map.of(),
        occurredAt);
  }

  private EventMessage eventMessage(ApplicationCreatedEvent event) {
    return eventMessage(event, event.occurredAt());
  }

  private ApplicationDataPayload applicationData(String laaReference) {
    return new ApplicationDataPayload(
        laaReference,
        null,
        ApplicationProvider.builder().officeCode("1A001B").build(),
        List.of(),
        Instant.parse("2026-08-18T09:00:00Z"),
        false,
        "FAMILY",
        "MATTER",
        List.of(),
        "{}",
        "GRANTED",
        AutoGrantedState.PENDING,
        Map.of(),
        Map.of(),
        null,
        null,
        null,
        List.of());
  }
}
