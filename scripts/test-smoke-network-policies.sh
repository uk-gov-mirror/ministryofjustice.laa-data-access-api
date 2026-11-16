#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHART_DIR="${ROOT_DIR}/.helm/data-access-api"
STACK_TEMPLATE="${ROOT_DIR}/.helm/smoke-test/stack.yaml"

smoke_chart="$(helm template smoke-test "${CHART_DIR}" \
  --values "${CHART_DIR}/values/uat.yaml" \
  --values "${CHART_DIR}/values/smoke-test.yaml" \
  --set spring.profile=smoke --set aws.region=eu-west-2)"
normal_chart="$(helm template normal-release "${CHART_DIR}" \
  --values "${CHART_DIR}/values/uat.yaml" \
  --set spring.profile=main --set aws.region=eu-west-2 \
  --show-only templates/networkPolicy.yaml)"

if ! grep -Eq '^kind:[[:space:]]*Deployment([[:space:]]|$)' <<< "${smoke_chart}" \
  || ! grep -Eq '^kind:[[:space:]]*Deployment([[:space:]]|$)' "${STACK_TEMPLATE}"; then
  echo "ERROR: Smoke manifests are missing deployments." >&2
  exit 1
fi
if grep -Eq '^kind:[[:space:]]*NetworkPolicy([[:space:]]|$)' <<< "${smoke_chart}"; then
  echo "ERROR: Smoke Helm release creates a NetworkPolicy." >&2
  exit 1
fi
if grep -Eq '^kind:[[:space:]]*NetworkPolicy([[:space:]]|$)' "${STACK_TEMPLATE}"; then
  echo "ERROR: Smoke dependency stack creates a NetworkPolicy." >&2
  exit 1
fi
if ! grep -Eq '^kind:[[:space:]]*NetworkPolicy([[:space:]]|$)' <<< "${normal_chart}" \
  || ! grep -Fq 'name: normal-release-data-access-api-allow-prometheus-scraping' <<< "${normal_chart}" \
  || ! grep -Fq 'podSelector: {}' <<< "${normal_chart}" \
  || ! grep -Fq 'component: monitoring' <<< "${normal_chart}"; then
  echo "ERROR: Normal Helm release is missing its monitoring NetworkPolicy." >&2
  exit 1
fi

echo "Smoke releases use existing network policies; normal releases retain the monitoring policy."
