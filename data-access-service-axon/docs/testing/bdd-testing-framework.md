# BDD Testing Framework

## Overview

Behaviour-driven development (BDD) describes a system's expected behaviour in examples that
developers, testers, and domain stakeholders can read. This project implements BDD as black-box
API scenarios: Cucumber executes Gherkin feature files against the live service in a disposable
smoke-test stack, rather than against Spring components in-process.

The BDD suite is a smoke test that verifies the service is deployed and behaves as expected, rather than a comprehensive 
test of every possible behaviour. It consists of happy-path scenarios that verify the service's end-to-end behaviour, 
including its interactions with the database, OAuth2, and SDS. It does not attempt to cover every edge case or error 
condition. Those are covered by unit and integration tests.

In GitHub Actions, the **Smoke tests** workflow runs these tests as a stage in the
PR and `main` build pipelines, after the candidate image is published and the
application vulnerability scan succeeds. The workflow deploys a per-run Kubernetes
stack in UAT and runs the BDD client on the runner through port-forwards. Subsequent
application deployments wait for smoke success; image publishing does not. See the
[infrastructure smoke-test guide](../../../docs/infrastructure-smoke-tests.md) for
details. The environment has:

* a PostgreSQL database with the same schema as production
* a mock OAuth2 server that issues tokens with the same claims as production
* a mock SDS server that returns the same small PDF for every document download
* the published candidate Axon image that subsequent application deployments use

The framework uses Cucumber 7, JUnit 5, PicoContainer for scenario-scoped dependency injection,
and Playwright's HTTP client. It exercises the API, PostgreSQL, OAuth2 resource-server security,
and the SDS document boundary together.

## Running the BDD Tests

Run the local Compose runner from the repository root to build the service, wait
for readiness, run `@smoke` scenarios, collect diagnostics and remove the disposable
stack and its database volume:

```bash
./scripts/run-infrastructure-smoke-tests.sh
```

See the [infrastructure smoke-test guide](../../../docs/infrastructure-smoke-tests.md)
for prerequisites, CI reports and deployment gating.

To manage the stack manually, build the Axon service JAR before bringing it up:

```bash
./gradlew :data-access-service-axon:bootJar
docker compose -f docker-compose.smoke-test.yml up --build --detach --wait --wait-timeout 180
./gradlew :data-access-service-axon:bddTest
```

The test task targets `http://localhost:9000` by default. It writes the HTML report to
`data-access-service-axon/build/reports/cucumber/index.html` and the machine-readable report to
`data-access-service-axon/build/reports/cucumber/cucumber.json`.

To rebuild the service image after changing application code, recreate the stack:

```bash
./gradlew :data-access-service-axon:bootJar
docker compose -f docker-compose.smoke-test.yml down --volumes --remove-orphans
docker compose -f docker-compose.smoke-test.yml up --build --detach
```

To run a tagged subset, pass Cucumber's tag expression as a system property:

```bash
./gradlew :data-access-service-axon:bddTest -Dcucumber.filter.tags='@smoke and @application'
```

To stop the stack and remove its disposable database volume:

```bash
docker compose -f docker-compose.smoke-test.yml down --volumes --remove-orphans
```

The suite can also target another compatible environment by setting the `LAA_BDD_*` environment
variables in `BddConfiguration`, including `LAA_BDD_BASE_URL`, `LAA_BDD_TOKEN_URL`,
`LAA_BDD_TOKEN_ISSUER_HOST`, timeout, and polling interval.

## Gherkin

Gherkin is Cucumber's plain-text language for executable examples. A `.feature` file contains a
`Feature` and one or more `Scenario`s. Each scenario is a sequence of `Given`, `When`, `Then`,
`And`, or `But` steps. Cucumber matches the step text to a Java method annotation and executes the
matching method. 

Tags, such as `@smoke`, `@application`, and `@prior-authority`, classify scenarios
and can be used to select runs.

For example, `application-prior-authority-lifecycle.feature` describes the lifecycle in business
language. Its `Given I am logged in as "smoke-test"` step obtains a token, while its `When` and
`Then` steps invoke and verify the live API. 

## Structure

The `bddTest` Gradle source set keeps black-box tests separate from unit and integration tests.
`RunCucumberTest` is the JUnit Platform suite: it selects the Cucumber engine and every feature
below the `features` classpath resource.

* Feature files: `src/bddTest/resources/features/`. Arrange related scenarios in subdirectories;
  the current smoke scenario is in `features/smoke/`.
* Java implementation: `src/bddTest/java/uk/gov/justice/laa/dstew/access/bdd/`.
* Reports: `build/reports/cucumber/` after a run.

### `steps`

Step-definition classes translate Gherkin into user-level API actions and assertions. Keep them
small and domain-oriented: `AuthenticationSteps` acquires a named token, `ApplicationSteps` drives
application creation, upload, submission and decisions, `PriorAuthoritySteps` drives equivalent
prior-authority actions, and `WorkListSteps` finds, assigns, and unassigns work-list items. Step
methods use Cucumber annotations such as `@Given`, `@When`, and `@Then`; parameters in annotation
expressions, for example `{string}`, receive quoted values from Gherkin.

### `fixtures`

Fixture factories construct valid, reusable request payloads for scenarios. `ApplicationFixtureFactory`
creates application drafts and grant decisions; `PriorAuthorityFixtureFactory` creates prior-authority
drafts and decisions. Put stable, valid defaults here and vary only the data that expresses the
scenario's behaviour.

### `client`

Domain API clients wrap endpoint paths, request construction, and eventual-consistency polling.
`ApplicationApi`, `PriorAuthorityApi`, and `WorkListApi` prevent endpoint details being duplicated
across step definitions.

### `support`

Shared technical helpers live here. `ApiClient` is the authenticated Playwright HTTP client,
`AuthTokenProvider` obtains mock Entra tokens, `HttpPolling` waits for asynchronous projections,
`ApiResponse` normalises HTTP responses, and `ScenarioContext` holds identifiers, versions, the
current office code, and the latest response for one scenario.

### `config`

`BddConfiguration` reads the `LAA_BDD_*` environment variables and provides target URLs,
authentication values, headers, and polling settings. Its defaults match the local smoke-test
stack.

### `hooks`

`ScenarioHooks` runs around every scenario. It validates the scenario's configuration when the
client is constructed and closes the Playwright request context afterwards.

### Root package

`RunCucumberTest` is the JUnit 5 suite entry point that discovers and runs the feature files.

### Framework packages

### SDS Mock

The smoke Compose stack replaces the external Secure Document Store (SDS) with WireMock service
`sds-wiremock-smoketest`. The application is configured to request SDS tokens and call SDS at that
service; production SDS is never contacted. Its mappings are in `infra/wiremock/sds/mappings/` and
cover client-credentials tokens, upload (`/save_file` and `/save_or_update_file`), lookup
(`/get_file`), download (`/files/smoke-document.pdf`), and deletion (`/delete_files`). The download
mapping returns a small PDF fixture for every scenario. The `/get_file` mapping builds `fileURL`
from the request `Host` header (`{{request.headers.Host}}`), so the download URL points at whichever
WireMock hostname the caller used; this requires WireMock global response templating.

To add an SDS behaviour, create or update a WireMock mapping JSON file in that directory. Define a
specific `request` match (method, path, query parameters, headers, and/or body) and the response
required by the scenario. Prefer the narrowest match that represents the intended SDS contract;
add a separate mapping for an error response instead of weakening a successful mapping. If a new
download payload is needed, add it as a WireMock `bodyFile` or response body and match the returned
file URL. Recreate the smoke stack after changing mappings so the container reads them:

```bash
docker compose -f docker-compose.smoke-test.yml down --volumes --remove-orphans
docker compose -f docker-compose.smoke-test.yml up --build --detach
```

### OAuth2 mock

The smoke Compose stack runs `navikt/mock-oauth2-server` as `mock-oauth2-server-smoketest`. The
BDD `AuthenticationSteps` calls its token endpoint through `AuthTokenProvider`, using the Gherkin
login name as the OAuth2 `client_id`. The mock configuration is
`infra/mock-oauth2/config-smoke-test.json`. Existing mappings issue a general caseworker token for
the client-credentials grant and provider-scoped tokens for `smoke-provider-1` and
`smoke-provider-2`; the latter include `LAA_ACCOUNTS` claims used by access-aware reads.

To model another caller, add a `requestMappings` entry beneath the `entra` token callback. Match
the `client_id` that a scenario will use in `Given I am logged in as "..."`, and supply claims that
the API expects: at minimum a suitable audience, scope or roles, and an `oid`; include
`LAA_ACCOUNTS` when modelling a provider. Then recreate the smoke stack. Do not put real
credentials or personal data in this fixture: it is test-only claim data.

## Creating new tests

### Writing features and scenarios

Create a `.feature` file under `src/bddTest/resources/features/`, normally in a directory that
matches its purpose, such as `smoke/`. Start with any tags, then a short outcome-focused `Feature:`
name. Add independent `Scenario:` blocks that describe one observable journey. Indent steps by two
spaces, use `Given` for preconditions, `When` for actions, `Then` for observable outcomes, and
`And` or `But` to continue the preceding kind of step.

```gherkin
@smoke @application
Feature: Application cancellation

  Scenario: A caseworker cancels a submitted application
	Given I am logged in as "smoke-test"
	When I create a valid application draft
	And I submit the application draft
	Then the application is eventually submitted
```

Write scenarios in domain language, not in terms of URLs, HTTP statuses, or JSON fields. Quote
variable text so it maps to Cucumber's `{string}` parameter, for example `"GRANTED"`. Reuse
existing steps where their meaning fits; add a new step only for a genuinely new behaviour. Each
scenario receives a fresh PicoContainer object graph and `ScenarioContext`, so it must establish
its own preconditions and cannot depend on another scenario's data.

### Writing new steps

Add a step-definition method to the relevant class in `steps`, or create a new domain-named class
there. Import `io.cucumber.java.en.Given`, `When`, or `Then` and annotate a public method with the
exact Gherkin expression. Use Cucumber expressions such as `{string}` for quoted parameters and
keep the annotation text readable and stable.

Constructor-inject shared collaborators. PicoContainer creates a fresh object graph for each
scenario, so inject `ScenarioContext` to retain IDs, versions, office code, and the latest response
between its steps. Read required values through its getter methods, which fail clearly if an earlier
step has not supplied them, and store values immediately after creating or retrieving resources.

Put API mechanics in the appropriate class in `client` rather than calling `ApiClient` directly
from many steps. Add a polling method through `HttpPolling` when the assertion concerns an
asynchronous projection. Use or extend a fixture factory for valid request payloads; do not embed
large JSON maps in a step method. A typical new step therefore: obtains data from the context,
builds a payload with a fixture, calls a domain client, records `context.lastResponse(...)`, asserts
the immediate result, and stores any identifier or version needed by later steps.

Add the matching Gherkin scenario, run it with `bddTest`, and use the generated Cucumber report to
diagnose a missing or ambiguous expression. Run a dry discovery check without executing scenarios
when needed:

```bash
./gradlew :data-access-service-axon:bddTest -Dcucumber.execution.dry-run=true
```
