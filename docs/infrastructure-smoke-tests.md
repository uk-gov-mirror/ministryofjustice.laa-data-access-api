# Infrastructure Smoke Tests

## Purpose

The Cucumber `@smoke` scenarios verify the live Axon HTTP API in a disposable stack: a
local Docker Compose stack, or a per-run Kubernetes stack in CI. They exercise application
and prior-authority lifecycles, including
PostgreSQL migrations, OAuth2 security, asynchronous projections and SDS document
operations. OAuth2 and SDS are test doubles; no deployed services are contacted.

These black-box checks complement the unit and integration suites. They use
`:data-access-service-axon:bddTest`, not the retired `infrastructureTest` harness.
See the [BDD framework guide](../data-access-service-axon/docs/testing/bdd-testing-framework.md)
for scenario structure and how to add tests.

## Run locally

Prerequisites: Docker with Compose, Java 25 and available host ports 9000, 9998 and
6432. Run from the repository root:

```bash
./scripts/run-infrastructure-smoke-tests.sh
```

The script uses fixed container names and a fixed Compose project name, so it will
stop and remove any smoke-test stack already running under that project, including
one started by a concurrent run of this script. It deletes that stack's disposable
database volume on exit. It does not touch the separate stack started by
`docker compose up` for local development.

| Step | Behaviour |
|------|----------|
| Build | Runs `./gradlew :data-access-service-axon:bootJar` for `Dockerfile.axon`. |
| Start | Starts `docker-compose.smoke-test.yml` under Compose project `laa-data-access-smoke-test`, with a bounded healthcheck wait. |
| Readiness | Checks the app's `/actuator/health` through the published host port with bounded retries. |
| Test | Runs `./gradlew :data-access-service-axon:bddTest -Dcucumber.filter.tags='@smoke' --rerun-tasks --console=plain`. Browser downloads are disabled because the suite uses Playwright's HTTP client. |
| Diagnostics | Collects Compose status and container logs before teardown, including on failure. |
| Cleanup | Removes the stack, network and database volume via an exit trap, including after startup or test failure. |

The script exits nonzero if the build, startup, readiness check or test run fails.
It also exits nonzero if collecting diagnostics or cleaning up fails, even when the
tests themselves passed. The test task always reruns, so a cached result from an
earlier run can never stand in for testing the service that this run just started.

The BDD defaults match the stack:

| Variable | Default |
|----------|---------|
| `LAA_BDD_BASE_URL` | `http://localhost:9000` |
| `LAA_BDD_TOKEN_URL` | `http://localhost:9998/entra/token` |
| `LAA_BDD_TOKEN_ISSUER_HOST` | `mock-oauth2-server-smoketest:9999` |

Use the direct Gradle command, rather than this stack-owning script, when targeting
another compatible environment. Additional `LAA_BDD_*` settings are described in the
BDD framework guide. The old `LAA_SMOKE_*` settings do not configure this suite.

## Reports and diagnostics

All paths are relative to `data-access-service-axon/build/`:

| Path | Contents |
|------|----------|
| `reports/cucumber/index.html` | Human-readable Cucumber report |
| `reports/cucumber/cucumber.json` | Machine-readable scenario results |
| `reports/tests/bddTest/` | Gradle HTML test report |
| `test-results/bddTest/` | JUnit XML results |
| `reports/smoke-stack/` | Container status, logs and host readiness response |

If startup fails, test reports may not exist; inspect the stack diagnostics first.

## GitHub Actions

The [Smoke tests](../.github/workflows/smoke-tests.yml) workflow is reusable only
(`workflow_call`). It does not run on its own. Two build workflows call it:

- [`build-feature.yml`](../.github/workflows/build-feature.yml) runs it on PRs, after
  `ecr-publish-service-image` and `vulnerability-scan-app` succeed.
- [`build-main.yml`](../.github/workflows/build-main.yml) runs it on pushes to `main`,
  after `ecr-publish-service-image` and `vulnerability-scan-app` succeed.

The candidate image is published first. The smoke job tests that exact
`published_image_version` tag, and the UAT, feature, staging and production deploy jobs
deploy the same tag only after the smoke job succeeds. A smoke failure therefore stops
the deployment chain. The Compose stack is no longer used in CI.

Both callers explicitly pass only `KUBE_CERT`, `KUBE_TOKEN`, `KUBE_CLUSTER`,
`KUBE_NAMESPACE` and `ECR_ROLE_TO_ASSUME`, declared by the reusable workflow.
`GITHUB_TOKEN` is available automatically; other repository secrets are not inherited.

## Kubernetes smoke tests (CI)

Each run creates a separate stack in the `uat` namespace, named from `GITHUB_RUN_ID` and
`GITHUB_RUN_ATTEMPT`:

| Resource | Source |
|----------|--------|
| PostgreSQL 17 (ephemeral `emptyDir`, random password in a Secret) | [`.helm/smoke-test/stack.yaml`](../.helm/smoke-test/stack.yaml) |
| Mock Entra (`navikt/mock-oauth2-server` 6.0.2) | Same manifest, config from [`infra/mock-oauth2/config-smoke-test.json`](../infra/mock-oauth2/config-smoke-test.json) |
| SDS WireMock (3.13.1, global templating) | Same manifest, mappings from [`infra/wiremock/sds/mappings`](../infra/wiremock/sds/mappings) |
| API release | [`.helm/data-access-api`](../.helm/data-access-api) with `values/uat.yaml` then `values/smoke-test.yaml` |

The API release uses `spring.profile=smoke`, a per-run database, the in-cluster mock
Entra issuer and SDS, JWT validation enabled, dev tokens enabled, and external
integration events, ingress, Grafana and port-forward pods disabled. The
`values/uat.yaml` defaults are unchanged. Services are `ClusterIP` only, and no ingress
is created.

The dependency pods have explicit CPU and memory requests and limits. PostgreSQL runs
as UID/GID 999 with `fsGroup=999` and a `PGDATA` subdirectory on the ephemeral volume,
so initialisation does not need root capabilities. SDS OAuth2 uses synthetic credentials,
not the shared UAT OAuth2 secret.

WireMock runs explicitly as UID/GID 1000 because its image defaults to root.
The ConfigMap mappings remain read-only; the image's root-only `uid` entrypoint
option is not used.

New smoke runs do not create NetworkPolicies. They rely on the existing UAT
namespace policies, including the policy that allows ingress from other pods in the
namespace. That policy permits cross-run connections; run-specific service names direct
normal traffic to the correct dependencies but do not enforce network isolation.
Confirm the namespace policies are deployed and enforced before running the stack.
Smoke releases do not add a monitoring ingress policy.

The runner reaches the API on `localhost:8080` and mock Entra on `localhost:9998` through
port-forwards. No database port-forward is needed. The BDD client sends the
`Host: smoke-<run>-mock-oauth2.<namespace>.svc.cluster.local:9999` header through
`LAA_BDD_TOKEN_ISSUER_HOST`, so token `iss` matches `ENTRA_ISSUER_URI` in the API. The SDS
`fileURL` is built from the request `Host` header, so it resolves to the run's WireMock.

[`scripts/kubernetes-smoke-stack.sh`](../scripts/kubernetes-smoke-stack.sh) manages the
stack:

| Command | Behaviour |
|---------|-----------|
| `up` | Creates labelled ConfigMaps, applies the rendered stack, waits for readiness, then runs `helm upgrade --install`. |
| `down` | Uninstalls the run's Helm release and deletes supported workload resources labelled with its run ID. |
| `cleanup-abandoned [hours]` | Runs `down` for every run whose resources are older than the threshold. |

Teardown exits nonzero if Helm uninstall or resource deletion fails. If Helm uninstall
fails, teardown still attempts to delete the dependency workloads, Services and Secrets,
but retains the labelled ConfigMaps so abandoned cleanup can rediscover the run and
retry the uninstall. Abandoned cleanup attempts every eligible run and exits nonzero
if any removal fails.

Dependency resources carry `app.kubernetes.io/part-of=laa-data-access-smoke-test`,
`laa-smoke-run-id` and `laa-smoke-created-epoch`; the API is removed through its
run-specific Helm release. The
[cleanup workflow](../.github/workflows/smoke-test-cleanup.yml) can be triggered manually
to remove stacks older than six hours left by cancelled or crashed runs. Previously
created dependency NetworkPolicies are not removed by this cleanup and require separate
platform-owned review and cleanup. Uninstalling an older Helm release can still remove
the monitoring NetworkPolicy owned by that release.

The smoke job runs under the `uat` environment, so its protection rules apply. It has a
30-minute timeout. A final `always()` step runs `down` for the run, and diagnostics
(pod status, events, and logs for each component) are included in the
`smoke-test-reports` artifact.

The image tag is passed from `ecr-publish-service-image` (`published_image_version`).
The workflow does not resolve a registry digest, so the tag is the immutable identity for
the tested image.

## Compose smoke tests (local)

The local runner described above remains available. It is not used in CI.
