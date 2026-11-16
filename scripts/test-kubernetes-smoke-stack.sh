#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "${TEST_DIR}"' EXIT
export MOCK_LOG="${TEST_DIR}/commands.log"
export KUBE_NAMESPACE=smoke-test RUN_ID=test-run

helm() {
  printf 'helm %s\n' "$*" >> "${MOCK_LOG}"
  if [ "${2:-}" = "${MOCK_HELM_FAIL_RELEASE:-}" ]; then
    return 1
  fi
}

kubectl() {
  printf 'kubectl %s\n' "$*" >> "${MOCK_LOG}"
  case "${3:-}" in
    get)
      if [ "${MOCK_GET_FAIL:-0}" -ne 0 ]; then
        return 1
      fi
      printf '%s\n' "${MOCK_RESOURCES:-}"
      ;;
    delete) return "${MOCK_DELETE_EXIT:-0}" ;;
    *) return 1 ;;
  esac
}
export -f helm kubectl

check_result() {
  local expected="$1"
  shift
  local actual=0
  : > "${MOCK_LOG}"
  bash "${ROOT_DIR}/scripts/kubernetes-smoke-stack.sh" "$@" > "${TEST_DIR}/output.log" 2>&1 || actual=$?
  if [ "${actual}" -ne "${expected}" ]; then
    cat "${TEST_DIR}/output.log" >&2
    echo "Expected exit ${expected}, got ${actual} for $*." >&2
    exit 1
  fi
}

check_result 0 down
grep -Fq 'delete deployments,services,secrets,configmaps,pods' "${MOCK_LOG}"

export MOCK_HELM_FAIL_RELEASE=smoke-test-run
check_result 1 down
grep -Fq 'delete deployments,services,secrets,pods' "${MOCK_LOG}"
if grep -Fq configmaps "${MOCK_LOG}"; then
  echo "Failed Helm uninstall must retain discovery ConfigMaps." >&2
  exit 1
fi

export MOCK_DELETE_EXIT=1
check_result 1 down
unset MOCK_HELM_FAIL_RELEASE
check_result 1 down
unset MOCK_DELETE_EXIT

export MOCK_RESOURCES=$'old-run 1\nnext-run 1\nfresh-run 9999999999'
export MOCK_HELM_FAIL_RELEASE=smoke-old-run
check_result 1 cleanup-abandoned 6
grep -Fq 'helm uninstall smoke-next-run ' "${MOCK_LOG}"
if grep -Fq 'helm uninstall smoke-fresh-run ' "${MOCK_LOG}"; then
  echo "Cleanup must not remove fresh stacks." >&2
  exit 1
fi

unset MOCK_HELM_FAIL_RELEASE
check_result 0 cleanup-abandoned 6
grep -Fq 'helm uninstall smoke-old-run ' "${MOCK_LOG}"
grep -Fq 'delete deployments,services,secrets,configmaps,pods' "${MOCK_LOG}"

export MOCK_GET_FAIL=1
check_result 1 cleanup-abandoned 6
if grep -Fq 'helm uninstall ' "${MOCK_LOG}"; then
  echo "Cleanup must stop when resource discovery fails." >&2
  exit 1
fi

echo "Smoke-stack cleanup checks passed (7 cases)."