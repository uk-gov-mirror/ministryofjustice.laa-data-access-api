#!/bin/bash
set -euo pipefail

# Default values
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5433}"
DB_NAME="${DB_NAME:-laa_data_access_api}"
DB_USERNAME="${DB_USERNAME:-laa_user}"
DB_PASSWORD="${DB_PASSWORD:-laa_password}"
AXON_DB_SCHEMA="${AXON_DB_SCHEMA:-axon}"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
OUTPUT_FILE="${1:-build/mass-generator-axon-${TIMESTAMP}.dump}"

echo "Dumping Axon schema to: $OUTPUT_FILE"
echo "Source: $DB_HOST:$DB_PORT/$DB_NAME (schema: $AXON_DB_SCHEMA)"

mkdir -p "$(dirname "$OUTPUT_FILE")"

PGPASSWORD="$DB_PASSWORD" pg_dump \
  --format=custom \
  --schema="$AXON_DB_SCHEMA" \
  --file="$OUTPUT_FILE" \
  --host="$DB_HOST" \
  --port="$DB_PORT" \
  --username="$DB_USERNAME" \
  "$DB_NAME"

echo "Dump completed: $OUTPUT_FILE"
ls -lh "$OUTPUT_FILE"
