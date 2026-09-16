CREATE TABLE uploaded_documents (
    document_id       UUID        NOT NULL,
    submission_id     UUID        NOT NULL,
    original_filename TEXT        NOT NULL,
    document_type     VARCHAR(255),
    metadata          JSONB       NOT NULL,
    PRIMARY KEY (document_id)
);

CREATE INDEX idx_uploaded_documents_submission_id
    ON uploaded_documents (submission_id);

ALTER TABLE prior_authority_current_state
    ADD COLUMN uploaded_document_ids JSONB NOT NULL DEFAULT '[]'::jsonb;

INSERT INTO uploaded_documents (
    document_id, submission_id, original_filename, document_type, metadata)
SELECT
    (document->>'documentId')::uuid AS document_id,
    source.prior_authority_id,
    document->>'fileName',
    document->>'documentType',
    jsonb_build_object(
        'fileType', document->>'fileType',
        'contentType', document->>'mediaType',
        'fileSize', (document->>'size')::bigint,
        'uploadedAt', document->>'uploadedAt',
        'sourceService', document->>'sourceService',
        'checksum', document->>'checksum')
FROM (
    SELECT prior_authority_id, payload
    FROM prior_authority_draft
    UNION ALL
    SELECT DISTINCT ON (prior_authority_id) prior_authority_id, payload
    FROM prior_authority_data
    ORDER BY prior_authority_id, data_version DESC
) source
CROSS JOIN LATERAL jsonb_array_elements(COALESCE(source.payload->'content'->'uploadedDocuments', '[]'::jsonb)) document
ON CONFLICT (document_id) DO NOTHING;

UPDATE prior_authority_current_state current_state
SET uploaded_document_ids = documents.document_ids
FROM (
    SELECT prior_authority_id, jsonb_agg(document_id ORDER BY uploaded_at) AS document_ids
    FROM (
        SELECT
            document_id,
            submission_id AS prior_authority_id,
            (metadata->>'uploadedAt')::timestamptz AS uploaded_at
        FROM uploaded_documents
    ) uploaded
    GROUP BY prior_authority_id
) documents
WHERE current_state.prior_authority_id = documents.prior_authority_id;