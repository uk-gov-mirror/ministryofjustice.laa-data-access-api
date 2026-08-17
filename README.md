[![Ministry of Justice Repository Compliance Badge](https://github-community.service.justice.gov.uk/repository-standards/api/laa-data-access-api/badge)](https://github-community.service.justice.gov.uk/repository-standards/laa-data-access-api)


# laa-data-access-api

## Overview

Source code for LAA Digital's Access Data Stewardship API, owned by the Access Data Stewardship team.

This API will provide a trusted API source of truth for the Civil Applications and Civil Decide projects for data
related to applications, proceedings, delegated functions, scope limitations, cost limitations and level of service.

### Monitoring (Prometheus & Grafana)

See `docs/monitoring.md` for details on how application metrics are collected, scraped by Prometheus, and visualised in Grafana dashboards.

### Network Policies

See `docs/network-policies.md` for details on the Kubernetes network policy that allows Prometheus to scrape metrics.

### Pre-commit hooks

See `docs/pre-commit-hooks.md` for information on setting up and using pre-commit hooks in this project.

### Project structure
Includes the following subprojects:

- `data-access-shared` - common Java classes packaged into a library to avoid unexpected dependencies - can depend
  on Spring Web, but must not depend on Spring Data (nor any database code)
- `data-access-api` - OpenAPI specification used for generating API stub interfaces and documentation.
- `data-access-service` - example REST API service with CRUD operations interfacing a JPA repository with PostgreSQL.
- `data-access-service-axon` - event-driven proof of concept using Axon aggregates and projections. See its
  [developer guide](data-access-service-axon/docs/README.md) for the architecture, linking logic, sensitive-data
  separation, and replay behaviour.

### To do items
- Continue to update this `README.md` file to include information such as what this project does.
- Agree provisional content of `CODEOWNERS` file and PR review policy (e.g. number of reviewers).
- Ensure the project has been added to the [Legal Aid Agency Snyk](https://app.snyk.io/org/legal-aid-agency) organisation.

## Build and run application

### Set up environment variables

Create or modify your '~/.zshrc' file to include the following environment variables:

Only set these 4 SDS variables in your `~/.zshrc`:

```bash
# SDS API Configuration (required)
export SDS_API_URL=https://dummy-sds-api-url
export SDS_API_BUCKET=dummy-sds-api-bucket-name
export SDS_API_CLIENT_REGISTRATION_ID=dummy-sds-api-client-registration-id
export SDS_API_PRINCIPAL_NAME=dummy-sds-api-principal-name
```

Then run with: `./gradlew bootRun --args='--spring.profiles.active=local'`

OAuth2 config is handled by `application-local.yml` - no ENTRA_* or AUTH_* variables needed.

---

**Option 2: Use environment variables (without local profile)**

Set all variables in your `~/.zshrc`:

```bash
# OAuth2 Resource Server Configuration
export ENTRA_ISSUER_URI=http://localhost:9999/entra
export ENTRA_JWK_SET_URI=http://localhost:9999/entra/jwks
export ENTRA_AUD=laa-data-access-api

# OAuth2 Client Configuration
export AUTH_CLIENT_ID=test
export AUTH_CLIENT_SECRET=test
export AUTH_SCOPE=api://laa-data-access-api/.default
export AUTH_TENANT_ID=entra

# SDS API Configuration
export SDS_API_URL=https://dummy-sds-api-url
export SDS_API_BUCKET=dummy-sds-api-bucket-name
export SDS_API_CLIENT_REGISTRATION_ID=dummy-sds-api-client-registration-id
export SDS_API_PRINCIPAL_NAME=dummy-sds-api-principal-name
```

Then run with: `./gradlew bootRun`

---

After setting environment variables, reload: `source ~/.zshrc`

### Developing application within Intellij
Java version 25 is required

To update to Java 25:

1. Download JDK 25 from https://www.oracle.com/uk/java/technologies/downloads/

2. Configure IntelliJ IDEA:
    - Go to **File** > **Project Structure** > **SDK**
    - Select **Add JDK from disk** and choose your Java 25 installation
    - Go to **IntelliJ IDEA** > **Settings** > **Build, Execution, Deployment** > **Build Tools** > **Gradle**
    - Set **Gradle JVM** to Java 25

### Build application
Execute

`./gradlew clean build`

### Run integration tests
Execute

`./gradlew integrationTest`

### Run infrastructure smoke tests

Infrastructure smoke tests run the built Docker image against a real Postgres database and
verify the live HTTP API. See [`docs/infrastructure-smoke-tests.md`](docs/infrastructure-smoke-tests.md)
for full details.

The script is useful for testing the application locally.
The smoke tests will also run in CI eventually.

```bash
./scripts/run-infrastructure-smoke-tests.sh
```

### Run application

To start up mock-oauth2-server, Postgres, Prometheus, and Grafana:

```bash
docker compose up -d
```

This will start:
- **Postgres** (port 5432) - Database
- **mock-oauth2-server** (port 9999) - OAuth2 test server for local authentication
- **Prometheus** (port 9090) - Metrics collection
- **Grafana** (port 3000) - Metrics visualization

Or if you want to use a different database name or credentials:

`docker compose run -p 5432:5432 -e POSTGRES_DB={database name} -e POSTGRES_USER={username} -e POSTGRES_PASSWORD={password} postgres`

Then run the application with the `local` profile:

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

The app will start on http://localhost:8080 with all OAuth2 configuration pre-configured.

### Executing endpoints

You can use curl or Postman to execute endpoints. First, get an authentication token:

```bash
# Get a token and save it as an environment variable
export TOKEN=$(./scripts/get-token.sh local)

# Call the API
curl -X GET "http://localhost:8080/api/v0/caseworkers" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Service-Name: CIVIL_APPLY"
```

You can also use the Swagger UI to execute endpoints, which is described below in the
[API documentation](#api-documentation) section.

#### Getting tokens from local mock-oauth2-server

You can use the helper script to fetch a token from the local mock-oauth2-server:

```bash
# Get a token and copy it to clipboard
./scripts/get-token.sh local --copy

# Get a token and decode it to see the payload
./scripts/get-token.sh local --decode
```

**How to use the token:**
1. Run the script with `--copy` to copy the token to your clipboard
2. Open Swagger UI at http://localhost:8080/swagger-ui/index.html
3. Click the "Authorize" button in the top right
4. Paste the token into the "Value" field
5. Click "Authorize" then "Close"

Now you can execute endpoints directly from Swagger UI.

---

## Authentication Across Environments

This section explains how to get JWT tokens for testing the API in different environments using mock-oauth2-server.

### Local Development

**Setup:**
```bash
# 1. Start the infrastructure
docker compose up -d

# 2. Run the app with local profile (includes all OAuth2 config)
./gradlew bootRun --args='--spring.profiles.active=local'
```

**Get a token:**
```bash
# Option 1: Copy to clipboard for Swagger UI
./scripts/get-token.sh local --copy

# Option 2: Save to environment variable for curl
export TOKEN=$(./scripts/get-token.sh local)
```

**Use the token:**
```bash
# With curl
curl -X GET "http://localhost:8080/api/v0/caseworkers" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Service-Name: CIVIL_APPLY"

# With Swagger UI - paste the token in the Authorize dialog
# http://localhost:8080/swagger-ui/index.html
```

**What's happening:**
- The `local` profile configures the app to accept JWTs from `http://localhost:9999/entra`
- The script requests a token from the same mock server
- No environment variables needed - everything is preconfigured

---

### UAT/Deployed Environments (PR and Feature Deployments)

PR and feature deployments run with the `preview` profile (security enabled). There are **two ways to authenticate**:

| Option | When to use | How |
|---|---|---|
| **Dev token** _(temporary)_ | Quick manual testing / Swagger UI while teams adopt the new flow | Use `swagger-caseworker-token` directly — no port-forward, no script |
| **Real JWT** (shared mock-oauth2) | **Recommended** — matches how the API is authenticated everywhere else | Fetch a signed token from the shared mock-oauth2 server (steps below) |

> **The `swagger-caseworker-token` dev token is temporary** and will be removed once teams are comfortable with the shared mock-oauth2 flow. Prefer **Option 2** for anything you'll rely on.

#### Option 1: Dev token (quickest, temporary)

`preview` deployments set `FEATURE_ENABLE_DEV_TOKEN=true`, so you can authenticate with the fixed dev token — no port-forward or script needed:

- **Swagger UI:** paste `swagger-caseworker-token` into the "Authorize" dialog
- **curl:** `-H "Authorization: Bearer swagger-caseworker-token"`

_This is a convenience for the transition period only — see the removal note above._

#### Option 2: Real JWT from the shared mock-oauth2 server

All PR and feature deployments share a **mock-oauth2 server** that is automatically deployed when code merges to `main`.

**Service name:** `laa-data-access-mock-oauth2-shared`

1. **Port-forward the shared mock-oauth2 service** (in a separate terminal):
   ```bash
   kubectl -n laa-data-access-api-uat port-forward \
     svc/laa-data-access-mock-oauth2-shared 9999:9999
   ```

2. **Fetch a test token** (the script defaults to the correct shared issuer):
   ```bash
   ./scripts/get-token.sh uat --copy --decode
   ```

   > **What is the issuer?** The token's issuer (`iss`) claim **must match** the deployment's
   > `ENTRA_ISSUER_URI` **exactly**. The UAT deployments use the full in-cluster FQDN
   > `http://laa-data-access-mock-oauth2-shared.laa-data-access-api-uat.svc.cluster.local:9999/entra`,
   > **not** the short service name. Spring does an exact string match, so a mismatch (e.g. using
   > `laa-data-access-mock-oauth2-shared:9999`) returns a 401. The script now defaults to the
   > correct FQDN; use `--issuer` only to override it.

**Then use the token:**
- The token is now in your clipboard (from the `--copy` flag)
- Paste it into Swagger UI's "Authorize" dialog
- Or use it with curl commands

**Note:** By default the issuer in the token will be `http://laa-data-access-mock-oauth2-shared.laa-data-access-api-uat.svc.cluster.local:9999/entra`, which matches what the UAT deployments expect. The issuer can be checked using `--decode`.

**Example: testing the `main` deployment**

The `main` branch is deployed to UAT and uses the shared mock-oauth2 instance, so the default issuer already applies:

```bash
# 1. Port-forward the shared mock-oauth2 (separate terminal)
kubectl -n laa-data-access-api-uat port-forward \
  svc/laa-data-access-mock-oauth2-shared 9999:9999

# 2. Mint a token (uses the correct FQDN issuer by default)
export TOKEN=$(./scripts/get-token.sh uat)

# 3. Call the main deployment
curl -X GET "https://main-laa-data-access-api-uat.cloud-platform.service.justice.gov.uk/api/v0/caseworkers" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Service-Name: CIVIL_APPLY"
```

**Targeting a different mock-oauth2 service:** If a deployment trusts a different issuer, pass its exact `ENTRA_ISSUER_URI` host to `--issuer`:
```bash
./scripts/get-token.sh uat --issuer <issuer-host>:9999 --copy
```

---

### Smoke Test Environment

For smoke tests that run via `docker-compose.smoke-test.yml`:

```bash
# Start smoke test infrastructure
docker compose -f docker-compose.smoke-test.yml up -d

# Get a token (uses port 9998)
./scripts/get-token.sh smoke --copy

# Use with smoke test API (runs on port 9000)
export TOKEN=$(./scripts/get-token.sh smoke)
curl -X GET "http://localhost:9000/api/v0/caseworkers" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Service-Name: CIVIL_APPLY"
```

**Smoke test ports:**
- App: http://localhost:9000
- Mock OAuth2: http://localhost:9998
- Postgres: localhost:6432

---

### Local SNS-to-SQS seam (event-driven auto-grant)

This repo owns the `data-access-events` SNS topic used by the event-driven auto-grant design
(mirrors the deployed `laa-data-access-api-uat/resources/sns.tf`). The consumer's queue is owned
by `laa-civil-decide-api`, so this repo's local stack also creates a private verification queue,
purely so the producer side can be proven independently without checking out that other repo.

```bash
docker compose -f docker-compose.localstack.yml up -d
AWS_REGION=eu-west-2 AWS_ACCESS_KEY_ID=local-access-key AWS_SECRET_ACCESS_KEY=local-secret-key \
  AWS_ENDPOINT_URL=http://localhost:4566 scripts/localstack/smoke-test.sh
```

See `scripts/localstack/init-localstack.sh` for the full resource shape and cross-repo integration
notes.

`data-access-service-axon` publishes to this topic when
`APPLICATION_INTEGRATION_EVENTS_TOPIC_ARN` is set. The Axon Compose service supplies the local ARN,
endpoint, region, and dummy credentials automatically. Deployed environments leave
`AWS_ENDPOINT_URL` and static credentials unset so the AWS SDK default credentials provider uses
the pod's IRSA service account, matching `laa-data-claims-event-service`.

#### Deployed topic configuration

The Axon Helm chart enables publication only where Cloud Platform has provisioned the SNS topic.
Its `aws.topicArnSecret` values select the `data-access-events-sns-topic` Kubernetes secret and
`topic_arn` key created by `laa-data-access-api-<environment>/resources/sns.tf`. Branch deployments
explicitly disable publication so ephemeral Application identifiers are not sent into the shared
UAT event path.

If Terraform recreates the topic, apply the Cloud Platform namespace resources before redeploying
Data Access. Terraform updates `topic_arn` in the existing secret, so no application code change
is required. Render the chart and confirm the topic environment variable before deployment:

```bash
helm template data-access-api-axon .helm/data-access-api-axon \
  --values .helm/data-access-api-axon/values/uat.yaml \
  --set image.repository=test --set image.tag=test |
  grep -A6 APPLICATION_INTEGRATION_EVENTS_TOPIC_ARN
```

---

### Troubleshooting Authentication

**Problem: "401 Unauthorized"**

Causes:
- Token expired
- Token from wrong environment
- Wrong issuer configuration

Solution:
```bash
# Get a fresh token
export TOKEN=$(./scripts/get-token.sh local)  # or 'uat'

# Decode to check claims
./scripts/get-token.sh local --decode

# Verify the 'iss' (issuer) matches your app's expected issuer
```

**Problem: Token works locally but not in UAT**

Cause: Issuer mismatch — local tokens won't work in UAT. The UAT deployments trust the full in-cluster FQDN issuer, so the token's `iss` must match exactly.

Solution:
```bash
# Use the uat environment — it defaults to the correct FQDN issuer
./scripts/get-token.sh uat --copy --decode

# Only override if a deployment trusts a different issuer:
./scripts/get-token.sh uat --issuer <issuer-host>:9999 --copy
```

**Problem: The token's `iss` isn't what you expect (e.g. a leftover value from another branch/spike)**

Cause: A stale `OAUTH_ISSUER_HOST` environment variable is exported in your shell. The script's precedence is `--issuer` flag > `OAUTH_ISSUER_HOST` env var > default, so an exported `OAUTH_ISSUER_HOST` silently overrides the correct FQDN default.

Solution:
```bash
# Check whether it's set in your current shell
echo "$OAUTH_ISSUER_HOST"

# Clear it, then fetch a fresh token
unset OAUTH_ISSUER_HOST
./scripts/get-token.sh uat --decode

# If it keeps coming back, it's persisted in a shell rc file — remove/comment it out there
grep -Rn "OAUTH_ISSUER_HOST" ~/.zshrc ~/.zprofile ~/.bashrc ~/.bash_profile 2>/dev/null
```

> **Tip:** Opening a fresh terminal (without the exported variable) is a quick way to confirm this is the cause.

---

### Quick Reference

| Environment | Command | Mock Server Port |
|------------|---------|------------------|
| **Local** | `./scripts/get-token.sh local --copy` | 9999 |
| **Smoke Test** | `./scripts/get-token.sh smoke --copy` | 9998 |
| **UAT/PR** | `./scripts/get-token.sh uat --copy` | 9999 (via port-forward) |

**Note:** The `uat` environment defaults to the shared instance's full FQDN issuer (`laa-data-access-mock-oauth2-shared.laa-data-access-api-uat.svc.cluster.local:9999`), which matches the deployments' `ENTRA_ISSUER_URI`. Override with `--issuer` only if a deployment trusts a different issuer.

**Required Headers for API Calls:**
- `Authorization: Bearer <token>`
- `X-Service-Name: CIVIL_APPLY` (or other valid service)

---

### Dependency lock files

Gradle [dependency locking](https://docs.gradle.org/current/userguide/dependency_locking.html) is enabled for this
project. Lock files (`gradle.lockfile`) exist in the root and each subproject directory, recording the exact resolved
versions of every dependency.

**Why lock files exist:** They ensure that builds are reproducible across different machines and CI environments. Without
them, Gradle may resolve dynamic or transitive dependency versions differently over time, leading to inconsistent builds.

**What happens when lock files don't match:** If a dependency version changes (e.g. a new dependency is added, upgraded,
or removed) but the lock files have not been updated, the build will **fail** with a dependency verification error. This
is intentional — it forces an explicit decision to accept the new set of resolved dependencies.

**To regenerate lock files** after changing dependencies, run:

- `./gradlew resolveAndLockAll`

This resolves all configurations across every subproject and writes updated lock files automatically. The updated
`gradle.lockfile` files should be committed alongside the dependency change.

### Useful gradle commands

Prior to pushing code, it's useful to run the following commands to check code style:
- `./gradlew checkStyleMain` - runs checkstyle on `main` source code
- `./gradlew checkStyleTest` - runs checkstyle on `test` source code

To generate coverage reports locally:
- `./gradlew jacocoAggregatedReport` - generates aggregate coverage report

The report will be available in `data-access-service/build/reports/jacoco/jacocoAggregatedReport/html/index.html`

### Dropping database tables (may not be applicable)
You may need to drop database tables manually prior to running app so Flyway can create the latest schema. To do this:
- Start up a Postgres management tool e.g. pgadmin
- Go to the laa database
- Drop the tables

### API documentation
#### Swagger UI
- http://localhost:8080/swagger-ui/index.html

For authentication tokens, see the [Authentication Across Environments](#authentication-across-environments) section above.

#### API docs (JSON)
- http://localhost:8080/v3/api-docs

### Actuator endpoints
The following actuator endpoints have been configured:
- http://localhost:8080/actuator
- http://localhost:8080/actuator/health
- http://localhost:8080/actuator/info

### Run the data generator

Each deployment to UAT will also deploy data-access-mass-generator as a separate pod. Initially it is scaled to 0,
however you can start the pod and connect to it via kubectl to be able to create performance testing data in that PR's database.

This is also available for the main deployment in UAT.

To start the pod, run the following command:

```bash
export KUBE_NAMESPACE=<uat namespace>
export RELEASE_NAME=<release name for the pod - you can find this in the deployment action>

./scripts/run-mass-generator-pod.sh <-- this will scale up the generator pod. When it is available it will connect to its shell

(in the shell run) java -jar mass-generator.jar <number of applications>
```

Once the command is complete, exit the shell and the pod will be scaled back to 0.
You can check the database to see the generated data or use swagger to execute endpoints.

### Generate and restore Axon mass data

The Axon mass-data generator runs as an opt-in Testcontainers integration test. It creates data through the service command-side use cases, validates the resulting projections, and exports a PostgreSQL custom-format dump with JSON metadata.

Generate a dump with a reproducible ten-application run:

```bash
./gradlew :data-access-service-axon:generateAxonMassDataDump \
  -PmassDataCount=10 \
  -PmassDataWorkers=2 \
  -PmassDataSeed=42 \
  -PmassDataDump=build/generated-dumps/axon-mass-data.dump
```

`massDataCount` is required. `massDataWorkers` defaults to `10`, `massDataSeed` is optional, and `massDataDump` defaults to `build/generated-dumps/axon-mass-data.dump` within `data-access-service-axon`. The task writes a `.metadata.json` file beside the dump with row counts for verification.

To restore into the local PostgreSQL database used by the Axon module's default datasource, start it first and explicitly confirm the destructive restore:

```bash
docker compose up -d postgres

RESTORE_TARGET=local \
CONFIRM_TARGET_RESTORE=yes \
./scripts/apply-mass-generator-axon-dump.sh \
  data-access-service-axon/build/generated-dumps/axon-mass-data.dump
```

Local restore resolves the running `postgres` Compose service, which uses the `laa-data-access-api_pgdata` volume. It targets database `laa_data_access_api` and user `laa_user`. Override `LOCAL_POSTGRES_CONTAINER`, `TARGET_DB_NAME`, `TARGET_DB_USERNAME`, `TARGET_DB_PASSWORD`, or `RESTORE_JOBS` when needed.

To restore into a Kubernetes PostgreSQL service, provide the target details and confirmation:

```bash
KUBE_NAMESPACE=<namespace> \
POSTGRES_SERVICE=<postgres-service> \
TARGET_DB_NAME=<database-name> \
TARGET_DB_USERNAME=<database-user> \
TARGET_DB_PASSWORD=<database-password> \
CONFIRM_TARGET_RESTORE=yes \
./scripts/apply-mass-generator-axon-dump.sh \
  data-access-service-axon/build/generated-dumps/axon-mass-data.dump
```

Both restore modes run `pg_restore --clean --if-exists`, so use a disposable database or one whose existing objects may be replaced.

## Additional information

### Libraries used
- [Spring Boot Actuator](https://docs.spring.io/spring-boot/reference/actuator/index.html) - used to provide various endpoints to help monitor the application, such as view application health and information.
- [Spring Boot Web](https://docs.spring.io/spring-boot/reference/web/index.html) - used to provide features for building the REST API implementation.
- [Spring Data JPA](https://docs.spring.io/spring-data/jpa/reference/jpa.html) - used to simplify database access and interaction, by providing an abstraction over persistence technologies, to help reduce boilerplate code.
- [Springdoc OpenAPI](https://springdoc.org/) - used to generate OpenAPI documentation. It automatically generates Swagger UI, JSON documentation based on your Spring REST APIs.
- [Lombok](https://projectlombok.org/) - used to help to reduce boilerplate Java code by automatically generating common
  methods like getters, setters, constructors etc. at compile-time using annotations.
- [MapStruct](https://mapstruct.org/) - used for object mapping, specifically for converting between different Java object types, such as Data Transfer Objects (DTOs)
  and Entity objects. It generates mapping code at compile code.
- [H2](https://www.h2database.com/html/main.html) - used to provide an example database and should not be used in production.

### Gradle plugin used
The project uses the `laa-spring-boot-gradle-plugin` Gradle plugin which provides
sensible defaults for the following plugins:

- [Checkstyle](https://docs.gradle.org/current/userguide/checkstyle_plugin.html)
- [Dependency Management](https://plugins.gradle.org/plugin/io.spring.dependency-management)
- [Jacoco](https://docs.gradle.org/current/userguide/jacoco_plugin.html)
- [Java](https://docs.gradle.org/current/userguide/java_plugin.html)
- [Maven Publish](https://docs.gradle.org/current/userguide/publishing_maven.html)
- [Spring Boot](https://plugins.gradle.org/plugin/org.springframework.boot)
- [Test Logger](https://github.com/radarsh/gradle-test-logger-plugin)
- [Versions](https://github.com/ben-manes/gradle-versions-plugin)

The plugin is provided by [laa-spring-boot-common](https://github.com/ministryofjustice/laa-spring-boot-common), where you can find
more information regarding (required) setup and usage.
