package uk.gov.justice.laa.dstew.access.service.sds;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import uk.gov.justice.laa.dstew.access.exception.FileConflictException;
import uk.gov.justice.laa.dstew.access.exception.ResourceNotFoundException;
import uk.gov.justice.laa.dstew.access.model.DocumentDeleteResponse;
import uk.gov.justice.laa.dstew.access.model.DocumentDownloadResponse;
import uk.gov.justice.laa.dstew.access.model.DocumentUpdateResponse;
import uk.gov.justice.laa.dstew.access.model.DocumentUploadResponse;
import uk.gov.justice.laa.dstew.access.model.SdsHealthResponse;

@ExtendWith(MockitoExtension.class)
class SdsServiceTest {

  @Mock private RestClient sdsRestClient;
  @Mock private SdsUploadResponseHandler sdsUploadResponseHandler;
  @Spy private ObjectMapper objectMapper = new ObjectMapper();

  @InjectMocks private SdsService sdsService;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(sdsService, "bucketName", "test-bucket");
  }

  @Test
  void givenValidFileAndApplicationId_whenSaveFile_thenReturnDocumentUploadResponse() {
    UUID applicationId = UUID.randomUUID();
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "test-file.pdf", "application/pdf", "test content".getBytes());
    DocumentUploadResponse expectedResponse = mock(DocumentUploadResponse.class);

    RestClient.RequestBodyUriSpec requestBodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
    RestClient.RequestBodySpec requestBodySpec = mock(RestClient.RequestBodySpec.class);
    RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

    when(sdsRestClient.post()).thenReturn(requestBodyUriSpec);
    when(requestBodyUriSpec.uri(endsWith("/save_file"))).thenReturn(requestBodySpec);
    when(requestBodySpec.contentType(MediaType.MULTIPART_FORM_DATA)).thenReturn(requestBodySpec);
    when(requestBodySpec.body(any(MultiValueMap.class))).thenReturn(requestBodySpec);
    when(requestBodySpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.onStatus(any(Predicate.class), any())).thenReturn(responseSpec);
    when(sdsUploadResponseHandler.handle(responseSpec)).thenReturn(responseSpec);
    when(responseSpec.body(DocumentUploadResponse.class)).thenReturn(expectedResponse);

    DocumentUploadResponse actualResponse = sdsService.saveFile(applicationId, file);

    assertThat(actualResponse).isEqualTo(expectedResponse);
    verify(sdsRestClient).post();
    verify(requestBodyUriSpec).uri(endsWith("/save_file"));
  }

  @Test
  void givenFileAlreadyExists_whenSaveFile_thenThrowFileConflictException() {
    UUID applicationId = UUID.randomUUID();
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "test-file.pdf", "application/pdf", "test content".getBytes());

    RestClient.RequestBodyUriSpec requestBodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
    RestClient.RequestBodySpec requestBodySpec = mock(RestClient.RequestBodySpec.class);
    RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

    when(sdsRestClient.post()).thenReturn(requestBodyUriSpec);
    when(requestBodyUriSpec.uri(endsWith("/save_file"))).thenReturn(requestBodySpec);
    when(requestBodySpec.contentType(MediaType.MULTIPART_FORM_DATA)).thenReturn(requestBodySpec);
    when(requestBodySpec.body(any(MultiValueMap.class))).thenReturn(requestBodySpec);
    when(requestBodySpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.onStatus(any(Predicate.class), any())).thenReturn(responseSpec);
    when(sdsUploadResponseHandler.handle(responseSpec)).thenReturn(responseSpec);
    when(responseSpec.body(DocumentUploadResponse.class))
        .thenThrow(new FileConflictException("File already exists"));

    assertThatExceptionOfType(FileConflictException.class)
        .isThrownBy(() -> sdsService.saveFile(applicationId, file))
        .withMessage("File already exists");
  }

  @Test
  @SuppressWarnings("unchecked")
  void
      givenFolderAndDocumentId_whenSaveFileWithDocumentId_thenOverridesFilenameAndReturnsDocumentUploadResponse() {
    UUID folderId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "real-name.pdf", "application/pdf", "test content".getBytes());
    DocumentUploadResponse expectedResponse = mock(DocumentUploadResponse.class);

    RestClient.RequestBodyUriSpec requestBodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
    RestClient.RequestBodySpec requestBodySpec = mock(RestClient.RequestBodySpec.class);
    RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

    when(sdsRestClient.post()).thenReturn(requestBodyUriSpec);
    when(requestBodyUriSpec.uri(endsWith("/save_file"))).thenReturn(requestBodySpec);
    when(requestBodySpec.contentType(MediaType.MULTIPART_FORM_DATA)).thenReturn(requestBodySpec);
    when(requestBodySpec.body(any(MultiValueMap.class))).thenReturn(requestBodySpec);
    when(requestBodySpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.onStatus(any(Predicate.class), any())).thenReturn(responseSpec);
    when(sdsUploadResponseHandler.handle(responseSpec)).thenReturn(responseSpec);
    when(responseSpec.body(DocumentUploadResponse.class)).thenReturn(expectedResponse);

    DocumentUploadResponse actualResponse = sdsService.saveFile(folderId, documentId, file);

    assertThat(actualResponse).isEqualTo(expectedResponse);

    ArgumentCaptor<MultiValueMap<String, HttpEntity<?>>> bodyCaptor =
        ArgumentCaptor.forClass(MultiValueMap.class);
    verify(requestBodySpec).body(bodyCaptor.capture());
    HttpEntity<?> filePart = bodyCaptor.getValue().getFirst("file");
    assertThat(filePart.getHeaders().getContentDisposition().getFilename())
        .isEqualTo(documentId.toString());
  }

  @Test
  void givenValidFile_whenSaveOrUpdateFile_thenReturnDocumentUpdateResponse() {
    UUID applicationId = UUID.randomUUID();
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "test-file.pdf", "application/pdf", "test content".getBytes());
    DocumentUpdateResponse expectedResponse = mock(DocumentUpdateResponse.class);

    RestClient.RequestBodyUriSpec requestBodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
    RestClient.RequestBodySpec requestBodySpec = mock(RestClient.RequestBodySpec.class);
    RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

    when(sdsRestClient.put()).thenReturn(requestBodyUriSpec);
    when(requestBodyUriSpec.uri(endsWith("/save_or_update_file"))).thenReturn(requestBodySpec);
    when(requestBodySpec.contentType(MediaType.MULTIPART_FORM_DATA)).thenReturn(requestBodySpec);
    when(requestBodySpec.body(any(MultiValueMap.class))).thenReturn(requestBodySpec);
    when(requestBodySpec.retrieve()).thenReturn(responseSpec);
    when(sdsUploadResponseHandler.handle(responseSpec)).thenReturn(responseSpec);
    when(responseSpec.body(DocumentUpdateResponse.class)).thenReturn(expectedResponse);

    DocumentUpdateResponse actualResponse = sdsService.saveOrUpdateFile(applicationId, file);

    assertThat(actualResponse).isEqualTo(expectedResponse);
    verify(sdsRestClient).put();
    verify(requestBodyUriSpec).uri(endsWith("/save_or_update_file"));
  }

  @Test
  void givenValidApplicationIdAndDocumentId_whenGetFile_thenReturnDocumentDownloadResponse() {
    UUID applicationId = UUID.randomUUID();
    String documentId = "test-file.pdf";
    DocumentDownloadResponse expectedResponse = mock(DocumentDownloadResponse.class);

    RestClient.RequestHeadersUriSpec requestHeadersUriSpec =
        mock(RestClient.RequestHeadersUriSpec.class);
    RestClient.RequestHeadersSpec requestHeadersSpec = mock(RestClient.RequestHeadersSpec.class);
    RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

    when(sdsRestClient.get()).thenReturn(requestHeadersUriSpec);
    when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
    when(requestHeadersSpec.accept(MediaType.APPLICATION_JSON)).thenReturn(requestHeadersSpec);
    when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.onStatus(
            any(Predicate.class), any(RestClient.ResponseSpec.ErrorHandler.class)))
        .thenReturn(responseSpec);
    when(responseSpec.body(DocumentDownloadResponse.class)).thenReturn(expectedResponse);

    DocumentDownloadResponse actualResponse = sdsService.getFile(applicationId, documentId);

    assertThat(actualResponse).isEqualTo(expectedResponse);
    verify(sdsRestClient).get();
  }

  @Test
  void givenFileNotFound_whenGetFile_thenThrowResourceNotFoundException() {
    UUID applicationId = UUID.randomUUID();
    String documentId = "test-file.pdf";

    RestClient.RequestHeadersUriSpec requestHeadersUriSpec =
        mock(RestClient.RequestHeadersUriSpec.class);
    RestClient.RequestHeadersSpec requestHeadersSpec = mock(RestClient.RequestHeadersSpec.class);
    RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

    when(sdsRestClient.get()).thenReturn(requestHeadersUriSpec);
    when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
    when(requestHeadersSpec.accept(MediaType.APPLICATION_JSON)).thenReturn(requestHeadersSpec);
    when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.onStatus(
            any(Predicate.class), any(RestClient.ResponseSpec.ErrorHandler.class)))
        .thenReturn(responseSpec);
    when(responseSpec.body(DocumentDownloadResponse.class))
        .thenThrow(new ResourceNotFoundException("File not found"));

    assertThatExceptionOfType(ResourceNotFoundException.class)
        .isThrownBy(() -> sdsService.getFile(applicationId, documentId))
        .withMessage("File not found");
  }

  @Test
  void givenValidApplicationIdAndFileIds_whenDeleteFiles_thenReturnDeleteResponse() {
    UUID applicationId = UUID.randomUUID();
    List<String> fileIds = List.of("file-1.pdf", "file-2.pdf");
    Map<String, Integer> sdsResults =
        Map.of(
            applicationId + "/file-1.pdf", 204,
            applicationId + "/file-2.pdf", 204);

    RestClient.RequestHeadersUriSpec requestHeadersUriSpec =
        mock(RestClient.RequestHeadersUriSpec.class);
    RestClient.RequestHeadersSpec requestHeadersSpec = mock(RestClient.RequestHeadersSpec.class);
    RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

    when(sdsRestClient.delete()).thenReturn(requestHeadersUriSpec);
    when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
    when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.body(any(ParameterizedTypeReference.class))).thenReturn(sdsResults);

    DocumentDeleteResponse response = sdsService.deleteFiles(applicationId, fileIds);

    assertThat(response.getResults()).isNotNull();
    assertThat(response.getResults().size()).isEqualTo(2);
    assertThat(response.getResults().stream().allMatch(r -> r.getStatus() == 204)).isTrue();
    verify(sdsRestClient).delete();
  }

  @Test
  void givenSdsReturnsNullBody_whenDeleteFiles_thenReturnEmptyResults() {
    UUID applicationId = UUID.randomUUID();
    List<String> fileIds = List.of("file-1.pdf", "file-2.pdf");

    RestClient.RequestHeadersUriSpec requestHeadersUriSpec =
        mock(RestClient.RequestHeadersUriSpec.class);
    RestClient.RequestHeadersSpec requestHeadersSpec = mock(RestClient.RequestHeadersSpec.class);
    RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

    when(sdsRestClient.delete()).thenReturn(requestHeadersUriSpec);
    when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
    when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.body(any(ParameterizedTypeReference.class))).thenReturn(null);

    DocumentDeleteResponse response = sdsService.deleteFiles(applicationId, fileIds);

    assertThat(response.getResults()).isNotNull();
    assertThat(response.getResults().isEmpty()).isTrue();
  }

  @Test
  void givenSdsServiceIsHealthy_whenGetHealth_thenReturnSdsHealthResponse() {
    SdsHealthResponse expectedResponse = mock(SdsHealthResponse.class);

    RestClient.RequestHeadersUriSpec requestHeadersUriSpec =
        mock(RestClient.RequestHeadersUriSpec.class);
    RestClient.RequestHeadersSpec requestHeadersSpec = mock(RestClient.RequestHeadersSpec.class);
    RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

    when(sdsRestClient.get()).thenReturn(requestHeadersUriSpec);
    when(requestHeadersUriSpec.uri(endsWith("/health"))).thenReturn(requestHeadersSpec);
    when(requestHeadersSpec.accept(MediaType.APPLICATION_JSON)).thenReturn(requestHeadersSpec);
    when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.body(SdsHealthResponse.class)).thenReturn(expectedResponse);

    SdsHealthResponse actualResponse = sdsService.getHealth();

    assertThat(actualResponse).isEqualTo(expectedResponse);
    verify(sdsRestClient).get();
    verify(requestHeadersUriSpec).uri(endsWith("/health"));
  }

  @SuppressWarnings("unchecked")
  @Test
  void givenSaveFileConflictPredicate_whenInvoked_thenMatchesConflictStatusAndHandlerThrows()
      throws Exception {
    UUID applicationId = UUID.randomUUID();
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "test-file.pdf", "application/pdf", "test content".getBytes());

    RestClient.RequestBodyUriSpec requestBodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
    RestClient.RequestBodySpec requestBodySpec = mock(RestClient.RequestBodySpec.class);
    RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

    ArgumentCaptor<Predicate<HttpStatusCode>> predicateCaptor =
        ArgumentCaptor.forClass(Predicate.class);
    ArgumentCaptor<RestClient.ResponseSpec.ErrorHandler> handlerCaptor =
        ArgumentCaptor.forClass(RestClient.ResponseSpec.ErrorHandler.class);

    when(sdsRestClient.post()).thenReturn(requestBodyUriSpec);
    when(requestBodyUriSpec.uri(endsWith("/save_file"))).thenReturn(requestBodySpec);
    when(requestBodySpec.contentType(MediaType.MULTIPART_FORM_DATA)).thenReturn(requestBodySpec);
    when(requestBodySpec.body(any(MultiValueMap.class))).thenReturn(requestBodySpec);
    when(requestBodySpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.onStatus(predicateCaptor.capture(), handlerCaptor.capture()))
        .thenReturn(responseSpec);
    when(sdsUploadResponseHandler.handle(responseSpec)).thenReturn(responseSpec);
    when(responseSpec.body(DocumentUploadResponse.class))
        .thenReturn(mock(DocumentUploadResponse.class));

    sdsService.saveFile(applicationId, file);

    Predicate<HttpStatusCode> conflictPredicate = predicateCaptor.getValue();
    assertThat(conflictPredicate.test(HttpStatus.CONFLICT)).isTrue();
    assertThat(conflictPredicate.test(HttpStatus.OK)).isFalse();

    assertThatExceptionOfType(FileConflictException.class)
        .isThrownBy(() -> handlerCaptor.getValue().handle(null, null))
        .withMessage("File already exists in SDS");
  }

  @Test
  void givenObjectMapperFails_whenBuildingMultipartBody_thenThrowIllegalStateException() {
    UUID applicationId = UUID.randomUUID();
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "test-file.pdf", "application/pdf", "test content".getBytes());

    doThrow(mock(JacksonException.class)).when(objectMapper).writeValueAsString(any());

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> sdsService.saveFile(applicationId, file))
        .withMessage("Unable to serialise SDS request body");
  }

  @SuppressWarnings("unchecked")
  @Test
  void givenGetFileNotFoundPredicate_whenInvoked_thenMatchesExpectedStatusesAndHandlerThrows()
      throws Exception {
    RestClient.RequestHeadersUriSpec requestHeadersUriSpec =
        mock(RestClient.RequestHeadersUriSpec.class);
    RestClient.RequestHeadersSpec requestHeadersSpec = mock(RestClient.RequestHeadersSpec.class);
    RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

    ArgumentCaptor<Predicate<HttpStatusCode>> predicateCaptor =
        ArgumentCaptor.forClass(Predicate.class);
    ArgumentCaptor<RestClient.ResponseSpec.ErrorHandler> handlerCaptor =
        ArgumentCaptor.forClass(RestClient.ResponseSpec.ErrorHandler.class);

    when(sdsRestClient.get()).thenReturn(requestHeadersUriSpec);
    when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
    when(requestHeadersSpec.accept(MediaType.APPLICATION_JSON)).thenReturn(requestHeadersSpec);
    when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.onStatus(predicateCaptor.capture(), handlerCaptor.capture()))
        .thenReturn(responseSpec);
    when(responseSpec.body(DocumentDownloadResponse.class))
        .thenReturn(mock(DocumentDownloadResponse.class));

    UUID applicationId = UUID.randomUUID();
    String documentId = "test-file.pdf";
    sdsService.getFile(applicationId, documentId);

    Predicate<HttpStatusCode> notFoundPredicate = predicateCaptor.getValue();
    assertThat(notFoundPredicate.test(HttpStatus.NOT_FOUND)).isTrue();
    assertThat(notFoundPredicate.test(HttpStatus.OK)).isFalse();

    assertThatExceptionOfType(ResourceNotFoundException.class)
        .isThrownBy(() -> handlerCaptor.getValue().handle(null, null));
  }
}
