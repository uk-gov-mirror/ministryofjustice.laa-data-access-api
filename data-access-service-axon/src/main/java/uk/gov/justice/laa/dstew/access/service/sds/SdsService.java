package uk.gov.justice.laa.dstew.access.service.sds;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_OCTET_STREAM;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import uk.gov.justice.laa.dstew.access.exception.FileConflictException;
import uk.gov.justice.laa.dstew.access.exception.ResourceNotFoundException;
import uk.gov.justice.laa.dstew.access.model.DocumentDeleteResponse;
import uk.gov.justice.laa.dstew.access.model.DocumentDeleteResult;
import uk.gov.justice.laa.dstew.access.model.DocumentDownloadResponse;
import uk.gov.justice.laa.dstew.access.model.DocumentUpdateResponse;
import uk.gov.justice.laa.dstew.access.model.DocumentUploadResponse;
import uk.gov.justice.laa.dstew.access.model.SdsHealthResponse;

/** Service class for interacting with the Secure Document Storage (SDS) API. */
@Service
@RequiredArgsConstructor
@Slf4j
public class SdsService {

  private static final String SAVE_FILE_ENDPOINT = "/save_file";
  private static final String SAVE_OR_UPDATE_FILE_ENDPOINT = "/save_or_update_file";
  private static final String GET_FILE_ENDPOINT = "/get_file";
  private static final String DELETE_FILES_ENDPOINT = "/delete_files";
  private static final String HEALTH_ENDPOINT = "/health";

  private static final String FILE_KEY_PARAM = "file_key";
  private static final String FILE_KEYS_PARAM = "file_keys";
  private static final String BUCKET_NAME_FIELD = "bucketName";
  private static final String FOLDER_FIELD = "folder";
  private static final String PATH_SEPARATOR = "/";

  @Qualifier("sdsRestClient")
  private final RestClient sdsRestClient;

  private final SdsUploadResponseHandler sdsUploadResponseHandler;

  private final ObjectMapper objectMapper;

  @Value("${app.sds-api.bucket-name}")
  private String bucketName;

  /**
   * Save a file in the SDS service.
   *
   * @param applicationId the application ID used as folder name
   * @param file the file to be saved
   * @return the file URL response from SDS
   */
  public DocumentUploadResponse saveFile(UUID applicationId, MultipartFile file) {
    String folderName = applicationId.toString();
    Map<String, String> bodyMap = new HashMap<>();
    bodyMap.put(BUCKET_NAME_FIELD, bucketName);
    bodyMap.put(FOLDER_FIELD, folderName);

    MultipartBodyBuilder builder = buildMultipartBody(file, bodyMap);

    return sdsUploadResponseHandler
        .handle(
            sdsRestClient
                .post()
                .uri(SAVE_FILE_ENDPOINT)
                .contentType(MULTIPART_FORM_DATA)
                .body(builder.build())
                .retrieve()
                .onStatus(
                    status -> status.isSameCodeAs(HttpStatus.CONFLICT),
                    (request, response) -> {
                      throw new FileConflictException("File already exists in SDS");
                    }))
        .body(DocumentUploadResponse.class);
  }

  /**
   * Save a file in the SDS service under an explicit, caller-supplied document ID rather than the
   * file's own name — used where the real filename must never reach SDS or its logs (e.g. Prior
   * Authority evidence, keyed by a server-minted document ID instead).
   *
   * @param folderId the folder/prefix the file is stored under
   * @param documentId the caller-minted document ID, used as both the SDS object key and filename
   * @param file the file to be saved
   * @return the file URL response from SDS
   */
  public DocumentUploadResponse saveFile(UUID folderId, UUID documentId, MultipartFile file) {
    Map<String, String> bodyMap = new HashMap<>();
    bodyMap.put(BUCKET_NAME_FIELD, bucketName);
    bodyMap.put(FOLDER_FIELD, folderId.toString());

    MultipartBodyBuilder builder = buildMultipartBody(file, bodyMap, documentId.toString());

    return sdsUploadResponseHandler
        .handle(
            sdsRestClient
                .post()
                .uri(SAVE_FILE_ENDPOINT)
                .contentType(MULTIPART_FORM_DATA)
                .body(builder.build())
                .retrieve()
                .onStatus(
                    status -> status.isSameCodeAs(HttpStatus.CONFLICT),
                    (request, response) -> {
                      throw new FileConflictException("File already exists in SDS");
                    }))
        .body(DocumentUploadResponse.class);
  }

  /**
   * Save or update a file in the SDS service.
   *
   * @param applicationId the application ID used as folder name
   * @param file the file to be saved or updated
   * @return the file URL response from SDS
   */
  public DocumentUpdateResponse saveOrUpdateFile(UUID applicationId, MultipartFile file) {
    Map<String, String> bodyMap =
        Map.of(BUCKET_NAME_FIELD, bucketName, FOLDER_FIELD, applicationId.toString());
    MultipartBodyBuilder builder = buildMultipartBody(file, bodyMap);

    return sdsUploadResponseHandler
        .handle(
            sdsRestClient
                .put()
                .uri(SAVE_OR_UPDATE_FILE_ENDPOINT)
                .contentType(MULTIPART_FORM_DATA)
                .body(builder.build())
                .retrieve())
        .body(DocumentUpdateResponse.class);
  }

  /**
   * Get the pre-signed download URL for a file from the SDS service.
   *
   * @param applicationId the application ID
   * @param documentId the document ID
   * @return the download URL response from SDS
   */
  public DocumentDownloadResponse getFile(UUID applicationId, String documentId) {
    String fileKey = buildFileKey(applicationId, documentId);

    return sdsRestClient
        .get()
        .uri(
            uriBuilder ->
                uriBuilder.path(GET_FILE_ENDPOINT).queryParam(FILE_KEY_PARAM, fileKey).build())
        .accept(APPLICATION_JSON)
        .retrieve()
        .onStatus(
            status -> status.value() == HttpStatus.NOT_FOUND.value(),
            (request, response) -> {
              throw new ResourceNotFoundException("File not found");
            })
        .body(DocumentDownloadResponse.class);
  }

  /**
   * Delete files from the SDS service.
   *
   * @param applicationId the application ID
   * @param fileIds the list of file IDs to be deleted
   * @return per-file deletion results
   */
  public DocumentDeleteResponse deleteFiles(UUID applicationId, List<String> fileIds) {
    List<String> fileKeys =
        fileIds.stream().map(fileId -> buildFileKey(applicationId, fileId)).toList();

    Map<String, Integer> sdsResults =
        sdsRestClient
            .delete()
            .uri(
                uriBuilder -> {
                  UriBuilder deleteFilesUri = uriBuilder.path(DELETE_FILES_ENDPOINT);
                  fileKeys.forEach(key -> deleteFilesUri.queryParam(FILE_KEYS_PARAM, key));
                  return deleteFilesUri.build();
                })
            .retrieve()
            .body(
                new org.springframework.core.ParameterizedTypeReference<Map<String, Integer>>() {});

    List<DocumentDeleteResult> results =
        sdsResults == null
            ? List.of()
            : fileIds.stream()
                .map(
                    fileId -> {
                      var result = new DocumentDeleteResult();
                      result.setDocumentId(fileId);
                      result.setStatus(sdsResults.get(buildFileKey(applicationId, fileId)));
                      return result;
                    })
                .toList();

    var response = new DocumentDeleteResponse();
    response.setResults(results);
    return response;
  }

  /**
   * Get the health status of the SDS service.
   *
   * @return the health response from SDS
   */
  public SdsHealthResponse getHealth() {
    return sdsRestClient
        .get()
        .uri(HEALTH_ENDPOINT)
        .accept(APPLICATION_JSON)
        .retrieve()
        .body(SdsHealthResponse.class);
  }

  private String buildFileKey(UUID applicationId, String documentId) {
    return applicationId.toString() + PATH_SEPARATOR + documentId;
  }

  private MultipartBodyBuilder buildMultipartBody(
      MultipartFile file, Map<String, String> bodyFields) {
    return buildMultipartBody(file, bodyFields, null);
  }

  private MultipartBodyBuilder buildMultipartBody(
      MultipartFile file, Map<String, String> bodyFields, String filenameOverride) {
    try {
      MultipartBodyBuilder builder = new MultipartBodyBuilder();
      MultipartBodyBuilder.PartBuilder part =
          builder.part("file", file.getResource()).contentType(APPLICATION_OCTET_STREAM);
      if (filenameOverride != null) {
        part.filename(filenameOverride);
      }
      builder.part("body", objectMapper.writeValueAsString(bodyFields));
      return builder;
    } catch (JacksonException e) {
      throw new IllegalStateException("Unable to serialise SDS request body", e);
    }
  }
}
