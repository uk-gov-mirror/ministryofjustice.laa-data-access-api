#!/bin/bash
set -euo pipefail

# Require explicit parameters
DUMP_FILE="${1:-}"
RESTORE_TARGET="${RESTORE_TARGET:-kubernetes}"
KUBE_NAMESPACE="${KUBE_NAMESPACE:-}"
POSTGRES_SERVICE="${POSTGRES_SERVICE:-}"
TARGET_DB_NAME="${TARGET_DB_NAME:-}"
TARGET_DB_USERNAME="${TARGET_DB_USERNAME:-}"
TARGET_DB_PASSWORD="${TARGET_DB_PASSWORD:-}"
CONFIRM_TARGET_RESTORE="${CONFIRM_TARGET_RESTORE:-}"

# Optional parameters with defaults
LOCAL_PORT="${LOCAL_PORT:-15432}"
REMOTE_PORT="${REMOTE_PORT:-5432}"
RESTORE_JOBS="${RESTORE_JOBS:-4}"
LOCAL_POSTGRES_CONTAINER="${LOCAL_POSTGRES_CONTAINER:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCAL_COMPOSE_FILE="$SCRIPT_DIR/../docker-compose.yml"

# Validation
if [ -z "$DUMP_FILE" ]; then
  echo "Usage: $0 <DUMP_FILE> [OPTIONS]" >&2
  echo "" >&2
  echo "Set RESTORE_TARGET=local for Docker Compose, or use the default Kubernetes target." >&2
  exit 1
fi

if [ "$RESTORE_TARGET" != "local" ] && [ "$RESTORE_TARGET" != "kubernetes" ]; then
  echo "Error: RESTORE_TARGET must be 'local' or 'kubernetes'" >&2
  exit 1
fi

if [ "$RESTORE_TARGET" = "local" ]; then
  TARGET_DB_NAME="${TARGET_DB_NAME:-laa_data_access_api}"
  TARGET_DB_USERNAME="${TARGET_DB_USERNAME:-laa_user}"
  TARGET_DB_PASSWORD="${TARGET_DB_PASSWORD:-laa_password}"
  if [ -z "$LOCAL_POSTGRES_CONTAINER" ]; then
    LOCAL_POSTGRES_CONTAINER="$(docker compose -f "$LOCAL_COMPOSE_FILE" ps -q postgres)"
  fi
  if [ -z "$LOCAL_POSTGRES_CONTAINER" ]; then
    echo "Error: Local PostgreSQL is not running." >&2
    echo "Start it with: docker compose up -d postgres" >&2
    echo "Or set LOCAL_POSTGRES_CONTAINER to a running PostgreSQL container." >&2
    exit 1
  fi
elif [ -z "$KUBE_NAMESPACE" ] || [ -z "$POSTGRES_SERVICE" ] || [ -z "$TARGET_DB_NAME" ] || \
     [ -z "$TARGET_DB_USERNAME" ] || [ -z "$TARGET_DB_PASSWORD" ]; then
  echo "Required Kubernetes environment variables:" >&2
  echo "  KUBE_NAMESPACE              - Kubernetes namespace" >&2
  echo "  POSTGRES_SERVICE            - PostgreSQL service name" >&2
  echo "  TARGET_DB_NAME              - Target database name" >&2
  echo "  TARGET_DB_USERNAME          - Target database username" >&2
  echo "  TARGET_DB_PASSWORD          - Target database password" >&2
  exit 1
fi

if [ ! -f "$DUMP_FILE" ]; then
  echo "Error: Dump file not found: $DUMP_FILE" >&2
  exit 1
fi

# Check for required commands
if [ "$RESTORE_TARGET" = "local" ]; then
  required_commands=(docker)
else
  required_commands=(kubectl pg_restore nc)
fi
for cmd in "${required_commands[@]}"; do
  if ! command -v "$cmd" &> /dev/null; then
    echo "Error: Required command not found: $cmd" >&2
    exit 1
  fi
done

# Confirm target
if [ "$CONFIRM_TARGET_RESTORE" != "yes" ]; then
  echo "========================================" >&2
  echo "RESTORE CONFIRMATION REQUIRED" >&2
  echo "========================================" >&2
  echo "Restore target:  $RESTORE_TARGET" >&2
  if [ "$RESTORE_TARGET" = "local" ]; then
    echo "Container:       $LOCAL_POSTGRES_CONTAINER" >&2
  else
    echo "Namespace:       $KUBE_NAMESPACE" >&2
    echo "Service:         $POSTGRES_SERVICE" >&2
  fi
  echo "Database:        $TARGET_DB_NAME" >&2
  echo "Username:        $TARGET_DB_USERNAME" >&2
  echo "Dump File:       $DUMP_FILE" >&2
  echo "" >&2
  echo "To proceed, set: CONFIRM_TARGET_RESTORE=yes" >&2
  exit 1
fi

if [ "$RESTORE_TARGET" = "local" ]; then
  container_dump="/tmp/$(basename "$DUMP_FILE")"
  echo "Restoring database from: $DUMP_FILE"
  echo "Target: Docker container $LOCAL_POSTGRES_CONTAINER/$TARGET_DB_NAME"
  docker cp "$DUMP_FILE" "$LOCAL_POSTGRES_CONTAINER:$container_dump"
  docker exec -e "PGPASSWORD=$TARGET_DB_PASSWORD" "$LOCAL_POSTGRES_CONTAINER" pg_restore \
    --clean \
    --if-exists \
    --no-owner \
    --no-privileges \
    --jobs="$RESTORE_JOBS" \
    --username="$TARGET_DB_USERNAME" \
    --dbname="$TARGET_DB_NAME" \
    "$container_dump"
else
  echo "Starting port-forward..."
  kubectl -n "$KUBE_NAMESPACE" port-forward "service/$POSTGRES_SERVICE" "$LOCAL_PORT:$REMOTE_PORT" &
  PF_PID=$!

  # Trap to clean up port-forward on exit
  trap "kill $PF_PID 2>/dev/null || true" EXIT

  # Wait for port-forward to be ready
  echo "Waiting for port-forward to become available..."
  for i in {1..30}; do
    if nc -z localhost "$LOCAL_PORT" 2>/dev/null; then
      echo "Port-forward ready"
      break
    fi
    if [ $i -eq 30 ]; then
      echo "Error: Port-forward did not become available" >&2
      exit 1
    fi
    sleep 1
  done

  echo "Restoring database from: $DUMP_FILE"
  echo "Target: localhost:$LOCAL_PORT/$TARGET_DB_NAME"

  PGPASSWORD="$TARGET_DB_PASSWORD" pg_restore \
    --clean \
    --if-exists \
    --no-owner \
    --no-privileges \
    --jobs="$RESTORE_JOBS" \
    --host=localhost \
    --port="$LOCAL_PORT" \
    --username="$TARGET_DB_USERNAME" \
    --dbname="$TARGET_DB_NAME" \
    "$DUMP_FILE"
fi

echo "Restore completed successfully"
