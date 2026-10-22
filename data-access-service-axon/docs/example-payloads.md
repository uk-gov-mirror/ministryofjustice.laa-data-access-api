# Example Payloads

Worked request/response examples for every `data-access-service-axon` endpoint, matching the
OpenAPI contracts in `data-access-api/open-api-*`. All examples assume the module is running via
`docker-compose.axon.yml` on `http://localhost:8082` and use the required `X-Service-Name` header
where applicable.

## Applications

### Create an application

`POST /api/v0/applications`

`applicationContent` is validated against the JSON schema selected by `X-Schema-Version`
(`data-access-service-axon/src/main/resources/schema/{version}/`). For `X-Schema-Version: 1` with
`X-Service-Name: CIVIL_APPLY`, the schema is `schema/1/ApplyApplication.json`, which requires `id`
(a UUID) and `submittedAt`, and each proceeding requires `id`, `leadProceeding`, and `description`
(see `schema/common/Proceeding.json`):

```bash
curl -i -X POST http://localhost:8082/api/v0/applications \
  -H "Content-Type: application/json" \
  -H "X-Service-Name: CIVIL_APPLY" \
  -H "X-Schema-Version: 1" \
  -d '{
    "status": "APPLICATION_IN_PROGRESS",
    "laaReference": "LAA-2026-000123",
    "applicationContent": {
      "id": "8c9e6c2e-4f1a-4e3a-9c2b-1a2b3c4d5e6f",
      "submittedAt": "2026-07-30T10:15:00Z",
      "status": "APPLICATION_IN_PROGRESS",
      "laaReference": "LAA-2026-000123",
      "proceedings": [
        {
          "id": "1e2d3c4b-5a69-4f78-8091-a2b3c4d5e6f7",
          "leadProceeding": true,
          "description": "Care order proceedings",
          "categoryOfLaw": "FAMILY",
          "matterType": "special children act (SCA)"
        }
      ]
    },
    "individuals": [
      {
        "firstName": "Jane",
        "lastName": "Doe",
        "dateOfBirth": "1990-04-12",
        "type": "CLIENT",
        "details": {
          "nationalInsuranceNumber": "AB123456C"
        }
      }
    ]
  }'
```

Response: `201 Created` with a `Location: /api/v0/applications/{applicationId}` header once the
projection is readable, or `202 Accepted` (same header) if the projection has not yet caught up.
Verified against a running instance: `HTTP/1.1 201` with
`Location: http://localhost:8082/api/v0/applications/8c9e6c2e-4f1a-4e3a-9c2b-1a2b3c4d5e6f`.

### Update an application and trigger submission

`PATCH /api/v0/applications/{id}` replaces `applicationContent` as a new immutable data version.
The following transition from in progress to submitted returns `204 No Content`, advances the
public Application version, keeps `autoGranted=PENDING`, and publishes one `ApplicationSubmitted` event
after commit:

```bash
curl -i -X PATCH \
  http://localhost:8082/api/v0/applications/8c9e6c2e-4f1a-4e3a-9c2b-1a2b3c4d5e6f \
  -H "Content-Type: application/json" \
  -H "X-Service-Name: CIVIL_APPLY" \
  -H "X-Correlation-Id: manual-dstew-2096" \
  -d '{
    "status": "APPLICATION_SUBMITTED",
    "applicationContent": {
      "id": "8c9e6c2e-4f1a-4e3a-9c2b-1a2b3c4d5e6f",
      "submittedAt": "2026-08-03T09:30:00Z",
      "status": "APPLICATION_SUBMITTED",
      "laaReference": "LAA-2026-000123",
      "proceedings": [{
        "id": "1e2d3c4b-5a69-4f78-8091-a2b3c4d5e6f7",
        "leadProceeding": true,
        "description": "Care order proceedings"
      }]
    }
  }'
```

When this repository's Axon and LocalStack Compose files are running, inspect the SNS-wrapped event
on its private producer verification queue:

```bash
AWS_ACCESS_KEY_ID=local-access-key AWS_SECRET_ACCESS_KEY=local-secret-key \
aws --region eu-west-2 --endpoint-url http://localhost:4566 sqs receive-message \
  --queue-url http://sqs.eu-west-2.localhost.localstack.cloud:4566/000000000000/data-access-events-producer-smoke-test-queue \
  --message-attribute-names All
```

In the parent Decide + Access workbench, use the shared consumer queue instead:

```bash
AWS_ACCESS_KEY_ID=local-access-key AWS_SECRET_ACCESS_KEY=local-secret-key \
aws --region eu-west-2 --endpoint-url http://localhost:4566 sqs receive-message \
  --queue-url http://sqs.eu-west-2.localhost.localstack.cloud:4566/000000000000/application-submitted-queue \
  --message-attribute-names All
```

Creating directly in `APPLICATION_SUBMITTED` also publishes. Creating in progress, an update that
remains submitted, a Decision write, or a ready write does not.

### Get applications (list, filtered and paginated)

`GET /api/v0/applications`

```bash
curl -s "http://localhost:8082/api/v0/applications?status=APPLICATION_IN_PROGRESS&matterType=special%20children%20act%20(SCA)&sortBy=LAST_UPDATED_DATE&orderBy=DESC&page=1&pageSize=20"
```

Example response (verified against a running instance, after creating the application above):

```json
{
  "paging": {
    "page": 1,
    "pageSize": 20,
    "itemsReturned": 1,
    "totalRecords": 1
  },
  "applications": [
    {
      "applicationId": "8c9e6c2e-4f1a-4e3a-9c2b-1a2b3c4d5e6f",
      "status": "APPLICATION_IN_PROGRESS",
      "submittedAt": "2026-07-30T10:15:00Z",
      "lastUpdated": "2026-07-30T11:09:33.392423Z",
      "usedDelegatedFunctions": null,
      "categoryOfLaw": null,
      "matterType": null,
      "assignedTo": null,
      "autoGrant": null,
      "clientFirstName": "Jane",
      "clientLastName": "Doe",
      "clientDateOfBirth": "1990-04-12",
      "laaReference": "LAA-2026-000123",
      "officeCode": null,
      "isLead": true,
      "linkedApplications": []
    }
  ]
}
```

### Get an application by ID

`GET /api/v0/applications/{id}`

```bash
curl -s http://localhost:8082/api/v0/applications/8c9e6c2e-4f1a-4e3a-9c2b-1a2b3c4d5e6f
```

Example response (verified against a running instance):

```json
{
  "applicationId": "8c9e6c2e-4f1a-4e3a-9c2b-1a2b3c4d5e6f",
  "status": "APPLICATION_IN_PROGRESS",
  "laaReference": "LAA-2026-000123",
  "lastUpdated": "2026-07-30T11:09:33.392423Z",
  "assignedTo": null,
  "submittedAt": "2026-07-30T10:15:00Z",
  "isLead": true,
  "usedDelegatedFunctions": null,
  "autoGrant": null,
  "decisionStatus": null,
  "opponents": [],
  "proceedings": [
    {
      "proceedingId": "13c7a2ee-0d05-4d3a-8d0b-6b4194756ab5",
      "proceedingType": null,
      "proceedingDescription": "Care order proceedings",
      "delegatedFunctionsDate": null,
      "categoryOfLaw": null,
      "matterType": null,
      "levelOfService": null,
      "substantiveCostLimitation": null,
      "scopeLimitations": [],
      "involvedChildren": [],
      "meritsDecision": null
    }
  ],
  "provider": null,
  "version": 0
}
```

Note: `proceedingType`, `categoryOfLaw`, and `matterType` on the response are currently only
populated from fields the read-model mapper projects; supplying them in `applicationContent`
does not yet surface them here.

### Exercise manual-task visibility

This sequence exercises the DSTEW-2093 contract end to end: a submitted Application starts with
`autoGranted=PENDING`, is absent from the manual-task query, and becomes visible only after the ready
operation records `autoGranted=MANUAL`. It uses fresh identifiers so the sequence can be repeated.

Create a submitted Application:

```bash
APP_ID=$(uuidgen | tr '[:upper:]' '[:lower:]')
PROCEEDING_ID=$(uuidgen | tr '[:upper:]' '[:lower:]')

curl -i -X POST http://localhost:8082/api/v0/applications \
  -H "Content-Type: application/json" \
  -H "X-Service-Name: CIVIL_APPLY" \
  -H "X-Schema-Version: 1" \
  --data-binary @- <<JSON
{
  "applicationType": "INITIAL",
  "status": "APPLICATION_SUBMITTED",
  "laaReference": "LAA-MANUAL-READY",
  "applicationContent": {
    "id": "$APP_ID",
    "submittedAt": "2026-08-03T12:00:00Z",
    "status": "APPLICATION_SUBMITTED",
    "laaReference": "LAA-MANUAL-READY",
    "proceedings": [
      {
        "id": "$PROCEEDING_ID",
        "leadProceeding": true,
        "description": "Manual readiness test",
        "categoryOfLaw": "FAMILY",
        "matterType": "special children act (SCA)"
      }
    ]
  },
  "individuals": [
    {
      "firstName": "Manual",
      "lastName": "Readiness",
      "dateOfBirth": "1990-04-12",
      "type": "CLIENT",
      "details": {
        "nationalInsuranceNumber": "AB123456C"
      }
    }
  ]
}
JSON
```

Confirm it is not yet a manual task. The selected result should be an empty array because the
Application has `autoGranted=PENDING`:

```bash
curl -s "http://localhost:8082/api/v0/applications?status=APPLICATION_SUBMITTED&autoGranted=MANUAL&page=1&pageSize=20" \
  | jq --arg id "$APP_ID" '.applications | map(select(.applicationId == $id))'
```

Record manual readiness using the current Application version (`0` immediately after creation):

```bash
curl -i -X PATCH "http://localhost:8082/api/v0/applications/$APP_ID/ready" \
  -H "Content-Type: application/json" \
  -H "X-Service-Name: CIVIL_DECIDE" \
  -d '{"applicationVersion":0}'
```

The first call returns `204 No Content`. Repeating the same call is idempotent and returns
`200 OK` without appending another event. A submitted Application that is still undecided returns
`409 Conflict` for a genuinely stale version; an in-progress Application returns
`422 Unprocessable Content`.

Confirm the outcome and incremented version through direct lookup:

```bash
curl -s "http://localhost:8082/api/v0/applications/$APP_ID" \
  | jq '{applicationId, status, autoGrant, version}'
```

Expected fields are `status: "APPLICATION_SUBMITTED"`, `autoGrant: false`, and `version: 1`.
The same Application should now appear in the manual-task query:

```bash
curl -s "http://localhost:8082/api/v0/applications?status=APPLICATION_SUBMITTED&autoGranted=MANUAL&page=1&pageSize=20" \
  | jq --arg id "$APP_ID" '.applications | map(select(.applicationId == $id))'
```

### Get the application certificate

`GET /api/v0/applications/{id}/certificate`

```bash
curl -s http://localhost:8082/api/v0/applications/8c9e6c2e-4f1a-4e3a-9c2b-1a2b3c4d5e6f/certificate \
  -H "X-Service-Name: CIVIL_DECIDE"
```

Returns the free-form certificate object stored on the application's immutable data version
(populated after a `GRANTED` decision). Verified against a running instance, returning exactly the
`certificate` object supplied on the decision request:

```json
{
  "issuedDate": "2026-07-30",
  "certificateNumber": "CERT-2026-000123"
}
```

### Get application history

`GET /api/v0/applications/{id}/history-search`

```bash
curl -s "http://localhost:8082/api/v0/applications/8c9e6c2e-4f1a-4e3a-9c2b-1a2b3c4d5e6f/history-search?eventType=APPLICATION_CREATED&eventType=APPLICATION_NOTE_CREATED" \
  -H "X-Service-Name: CIVIL_DECIDE"
```

`eventType` is optional and repeatable; omitting it returns all `DomainEventType` values
(`APPLICATION_CREATED`, `APPLICATION_UPDATED`, `APPLICATION_GROUP_CREATED`,
`APPLICATION_GROUP_JOINED`, `ASSIGN_APPLICATION_TO_CASEWORKER`,
`UNASSIGN_APPLICATION_TO_CASEWORKER`, `APPLICATION_MAKE_DECISION_REFUSED`,
`APPLICATION_MAKE_DECISION_GRANTED`, `APPLICATION_NOTE_CREATED`).

Example response (verified against a running instance, after creating the application above):

```json
{
  "events": [
    {
      "applicationId": "8c9e6c2e-4f1a-4e3a-9c2b-1a2b3c4d5e6f",
      "caseworkerId": null,
      "domainEventType": "APPLICATION_CREATED",
      "createdAt": "2026-07-30T11:09:33.392423Z",
      "createdBy": "CIVIL_APPLY",
      "eventDescription": null
    }
  ]
}
```

### Assign an open work-list item to yourself

`POST /api/v0/work-list/{itemId}/assign` assigns the item to the authenticated JWT's Entra OID.
The request body never accepts a caseworker ID.

```bash
curl -i -X POST http://localhost:8082/api/v0/work-list/8c9e6c2e-4f1a-4e3a-9c2b-1a2b3c4d5e6f/assign \
  -H "Content-Type: application/json" \
  -H "X-Service-Name: CIVIL_DECIDE" \
  -d '{
    "expectedAssignmentVersion": 0,
    "eventHistory": {
      "eventDescription": "Assigned during triage"
    }
  }'
```

Response: `200 OK` with an empty body. The authenticated OID must be a UUID; it is persisted as
the assignment identity without looking up a `caseworkers` table.

### Make a decision on an application

`PATCH /api/v0/applications/{id}/decision`

```bash
curl -i -X PATCH http://localhost:8082/api/v0/applications/8c9e6c2e-4f1a-4e3a-9c2b-1a2b3c4d5e6f/decision \
  -H "Content-Type: application/json" \
  -H "X-Service-Name: CIVIL_DECIDE" \
  -d '{
    "overallDecision": "GRANTED",
    "autoGranted": false,
    "applicationVersion": 2,
    "proceedings": [
      {
        "proceedingId": "13c7a2ee-0d05-4d3a-8d0b-6b4194756ab5",
        "meritsDecision": {
          "decision": "GRANTED",
          "reason": "Meets merits test",
          "justification": "Sufficient prospects of success and reasonableness of costs."
        }
      }
    ],
    "certificate": {
      "certificateNumber": "CERT-2026-000123",
      "issuedDate": "2026-07-30"
    },
    "eventHistory": {
      "eventDescription": "Decision made following panel review"
    }
  }'
```

`certificate` is required when `overallDecision` is `GRANTED`. `applicationVersion` must match the
current version returned by `GET /api/v0/applications/{id}` (optimistic concurrency control) — use
`proceedingId` values from that same response. A stale or unknown version/application returns
`409 Conflict`, e.g. `{"detail":"Application with id ... and version ... not found", "status":409}`.
The version increments on every command against the aggregate (create, assign, unassign, decision,
notes), so re-fetch it immediately before deciding.

The authenticated JWT's Entra OID is the decision actor and must match the stored assignment OID.
It is not accepted in the request JSON.

Response: `204 No Content`.

### Add a note to an application

`POST /api/v0/applications/{id}/notes`

```bash
curl -i -X POST http://localhost:8082/api/v0/applications/8c9e6c2e-4f1a-4e3a-9c2b-1a2b3c4d5e6f/notes \
  -H "Content-Type: application/json" \
  -H "X-Service-Name: CIVIL_DECIDE" \
  -d '{
    "notes": "Called client to confirm updated address."
  }'
```

Response: `204 No Content`.

### Get notes for an application

`GET /api/v0/applications/{id}/notes`

```bash
curl -s http://localhost:8082/api/v0/applications/8c9e6c2e-4f1a-4e3a-9c2b-1a2b3c4d5e6f/notes
```

Example response (verified against a running instance):

```json
{
  "notes": [
    {
      "note": "Called client to confirm updated address.",
      "createdAt": "2026-07-30T11:10:25.499456299Z",
      "createdBy": null
    }
  ]
}
```

## Individuals

### Get individuals (filtered and paginated)

`GET /api/v0/individuals`

```bash
curl -s "http://localhost:8082/api/v0/individuals?applicationId=8c9e6c2e-4f1a-4e3a-9c2b-1a2b3c4d5e6f&individualType=CLIENT&include=CLIENT_DETAILS&page=1&pageSize=20" \
  -H "X-Service-Name: CIVIL_DECIDE"
```

Example response (verified against a running instance):

```json
{
  "paging": {
    "page": 1,
    "pageSize": 20,
    "itemsReturned": 1,
    "totalRecords": 1
  },
  "individuals": [
    {
      "clientId": "91b81b99-f890-4c10-ac68-14310df7caaf",
      "lastNameAtBirth": null,
      "previousApplicationId": null,
      "relationshipToInvolvedChildren": null,
      "correspondenceAddressType": null,
      "appliedPreviously": null,
      "firstName": "Jane",
      "lastName": "Doe",
      "dateOfBirth": "1990-04-12",
      "correspondenceAddress": null,
      "details": {
        "nationalInsuranceNumber": "AB123456C"
      },
      "type": "CLIENT"
    }
  ]
}
```

`include=CLIENT_DETAILS` is required to populate the free-form `details` object; omit it to receive
only the core individual fields.

## Common notes

- All application-command and most query endpoints require the `X-Service-Name` header, one of
  `CIVIL_APPLY` or `CIVIL_DECIDE` — see `open-api-common/components.yml`.
- `X-Schema-Version` (default `1`) on `POST /api/v0/applications` selects the schema used to
  validate `applicationContent`.
- UUIDs above are illustrative; substitute real IDs returned from `createApplication`,
  `getApplications`, etc.
- Error responses (`400`, `401`, `403`, `404`, `500`) are documented per-endpoint in
  `data-access-api/open-api-applications/resources.yml` and `open-api-individuals/resources.yml`.
