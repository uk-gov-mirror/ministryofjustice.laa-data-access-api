{{/*
  Define environment variables that can be "included" in deployment.yaml
*/}}
{{- define "dbConnectionDetails" }}
{{- if eq .Values.spring.profile "preview" }}
{{/*
For the preview branches, set DB connection details to Bitnami Postgres specific values
*/}}
- name: DB_NAME
  value: "postgres"
- name: DB_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ .Release.Name }}-postgresql
      key: postgres-password
- name: DB_HOST
  value: {{ .Release.Name }}-postgresql
{{- else if or (eq .Values.spring.profile "main") (eq .Values.spring.profile "unsecured") }}
{{/*
For the main branch, extract DB environment variables from rds-postgresql-instance-output secret
*/}}
- name: DB_NAME
  valueFrom:
    secretKeyRef:
      name: rds-postgresql-instance-output
      key: database_name
- name: DB_USERNAME
  valueFrom:
    secretKeyRef:
      name: rds-postgresql-instance-output
      key: database_username
- name: DB_PASSWORD
  valueFrom:
    secretKeyRef:
      name: rds-postgresql-instance-output
      key: database_password
- name: DB_HOST
  valueFrom:
    secretKeyRef:
      name: rds-postgresql-instance-output
      key: rds_instance_address
{{- else if eq .Values.spring.profile "smoke" }}
{{/*
For per-run Kubernetes smoke tests, use the ephemeral PostgreSQL deployed with the release
*/}}
- name: DB_NAME
  valueFrom:
    secretKeyRef:
      name: {{ .Release.Name }}-smoke-postgres
      key: database_name
- name: DB_USERNAME
  valueFrom:
    secretKeyRef:
      name: {{ .Release.Name }}-smoke-postgres
      key: database_username
- name: DB_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ .Release.Name }}-smoke-postgres
      key: database_password
- name: DB_HOST
  value: {{ .Release.Name }}-smoke-postgres
{{- end }}
{{- end }}

{{/*
  Define Sentry environment variables
*/}}
{{- define "sentryConfig" }}
{{- if .Values.sentry.enabled }}
- name: SENTRY_ENABLED
  value: "true"
- name: SENTRY_DSN
  valueFrom:
    secretKeyRef:
      name: laa-data-access-api-secrets
      key: SENTRY_DSN
- name: SENTRY_ENVIRONMENT
  value: {{ .Values.sentry.environment | quote }}
- name: SENTRY_TRACES_SAMPLE_RATE
  value: {{ .Values.sentry.tracesSampleRate | quote }}
{{- else }}
- name: SENTRY_ENABLED
  value: "false"
{{- end }}
{{- end }}

{{/*
  Define OAuth2/Entra ID environment variables for authentication

  UAT/Testing: Always uses shared mock-oauth2 (NO real Entra ID)
  Production: Uses real Azure Entra ID from secrets
*/}}
{{- define "oauth2Config" }}
{{- if and .Values.mockOAuth2 .Values.mockOAuth2.sharedInstance .Values.mockOAuth2.sharedInstance.enabled }}
{{/* UAT/Testing: Use shared mock-oauth2 instance - NO Entra ID */}}
- name: ENTRA_ISSUER_URI
  value: "http://{{ .Values.mockOAuth2.sharedInstance.serviceName }}.{{ .Values.mockOAuth2.sharedInstance.namespace }}.svc.cluster.local:9999/entra"
- name: ENTRA_JWK_SET_URI
  value: "http://{{ .Values.mockOAuth2.sharedInstance.serviceName }}.{{ .Values.mockOAuth2.sharedInstance.namespace }}.svc.cluster.local:9999/entra/jwks"
- name: ENTRA_AUD
  value: "laa-data-access-api"
{{- else }}
{{/* Production: Use real Azure Entra ID from secrets */}}
- name: ENTRA_ISSUER_URI
  valueFrom:
    secretKeyRef:
      name: laa-data-access-api-secrets
      key: ENTRA_ISSUER_URI
- name: ENTRA_JWK_SET_URI
  valueFrom:
    secretKeyRef:
      name: laa-data-access-api-secrets
      key: ENTRA_JWK_SET_URI
- name: ENTRA_AUD
  valueFrom:
    secretKeyRef:
      name: laa-data-access-api-secrets
      key: ENTRA_AUD
{{- end }}
{{- if and .Values.smokeTest .Values.smokeTest.enabled }}
- name: AUTH_CLIENT_ID
  value: "smoke-client"
- name: AUTH_CLIENT_SECRET
  value: "smoke-secret"
- name: AUTH_SCOPE
  value: "sds.mock"
- name: AUTH_TENANT_ID
  value: "smoke"
{{- else }}
- name: AUTH_CLIENT_ID
  valueFrom:
    secretKeyRef:
      name: laa-data-access-api-secrets
      key: AUTH_CLIENT_ID
- name: AUTH_CLIENT_SECRET
  valueFrom:
    secretKeyRef:
      name: laa-data-access-api-secrets
      key: AUTH_CLIENT_SECRET
- name: AUTH_SCOPE
  valueFrom:
    secretKeyRef:
      name: laa-data-access-api-secrets
      key: AUTH_SCOPE
- name: AUTH_TENANT_ID
  valueFrom:
    secretKeyRef:
      name: laa-data-access-api-secrets
      key: AUTH_TENANT_ID
{{- end }}
{{- end }}

{{/*
  Define feature environment variables for flags
*/}}
{{- define "featureConfig" }}
{{- if and .Values.featureFlags (hasKey .Values.featureFlags "enable_dev_token") }}
{{/* Use value from values.yaml if explicitly set */}}
- name: FEATURE_ENABLE_DEV_TOKEN
  value: {{ .Values.featureFlags.enable_dev_token | quote }}
{{- else }}
{{/* Default to secret value */}}
- name: FEATURE_ENABLE_DEV_TOKEN
  valueFrom:
    secretKeyRef:
      name: laa-data-access-api-secrets
      key: FEATURE_ENABLE_DEV_TOKEN
{{- end }}
{{- if and .Values.featureFlags (hasKey .Values.featureFlags "disable_security") }}
{{/* Use value from values.yaml if explicitly set */}}
- name: FEATURE_DISABLE_SECURITY
  value: {{ .Values.featureFlags.disable_security | quote }}
{{- else }}
{{/* Default to secret value */}}
- name: FEATURE_DISABLE_SECURITY
  valueFrom:
    secretKeyRef:
      name: laa-data-access-api-secrets
      key: FEATURE_DISABLE_SECURITY
{{- end }}
{{- if .Values.featureFlags }}
{{- range $key, $value := .Values.featureFlags }}
{{- if and (ne $key "enable_dev_token") (ne $key "disable_security") }}
- name: FEATURE_{{ upper $key }}
  value: {{ $value | quote }}
{{- end }}
{{- end }}
{{- end }}
{{- end }}

{{/*
  Define additional environment variables for short-lived environments
*/}}
{{- define "extraEnvConfig" }}
{{- if .Values.extraEnv }}
{{- range $key, $value := .Values.extraEnv }}
- name: {{ $key }}
  value: {{ $value | quote }}
{{- end }}
{{- end }}
{{- end }}

{{/*
  Define SDS API environment variables
*/}}
{{- define "sdsApiConfig" }}
{{- if and .Values.smokeTest .Values.smokeTest.enabled }}
{{/* Per-run smoke tests: call the release's WireMock SDS instead of the real SDS */}}
- name: SDS_API_URL
  value: "http://{{ .Release.Name }}-sds:8080"
- name: SDS_API_TOKEN_URI
  value: "http://{{ .Release.Name }}-sds:8080/oauth/token"
- name: SDS_API_BUCKET
  value: {{ .Values.smokeTest.sds.bucket | quote }}
- name: SDS_API_CLIENT_REGISTRATION_ID
  value: {{ .Values.smokeTest.sds.clientRegistrationId | quote }}
- name: SDS_API_PRINCIPAL_NAME
  value: {{ .Values.smokeTest.sds.principalName | quote }}
{{- else }}
- name: SDS_API_URL
  valueFrom:
    secretKeyRef:
      name: laa-data-access-api-secrets
      key: SDS_API_URL
- name: SDS_API_BUCKET
  valueFrom:
    secretKeyRef:
      name: laa-data-access-api-secrets
      key: SDS_API_BUCKET
- name: SDS_API_CLIENT_REGISTRATION_ID
  valueFrom:
    secretKeyRef:
      name: laa-data-access-api-secrets
      key: SDS_API_CLIENT_REGISTRATION_ID
- name: SDS_API_PRINCIPAL_NAME
  valueFrom:
    secretKeyRef:
      name: laa-data-access-api-secrets
      key: SDS_API_PRINCIPAL_NAME
{{- end }}
{{- end }}