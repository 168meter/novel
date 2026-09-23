#!/bin/sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.prod}"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT_DIR/compose.prod.yml}"
BOOTSTRAP_SERVER='localhost:19092'
KAFKA_BIN='/opt/kafka/bin'
MAIN_RETENTION='retention.ms=86400000,retention.bytes=134217728'
DLT_RETENTION='retention.ms=604800000,retention.bytes=268435456'

for required_file in "$ENV_FILE" "$COMPOSE_FILE"; do
  if [ ! -f "$required_file" ]; then
    echo "Required file is missing: $required_file" >&2
    exit 66
  fi
done
command -v docker >/dev/null 2>&1 || {
  echo 'docker is required.' >&2
  exit 69
}

compose() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

kafka_command() {
  tool=$1
  shift
  compose exec -T kafka "$KAFKA_BIN/$tool" "$@"
}

attempt=0
until kafka_command kafka-broker-api-versions.sh --bootstrap-server "$BOOTSTRAP_SERVER" >/dev/null 2>&1; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 60 ]; then
    echo 'Kafka did not become ready within 120 seconds.' >&2
    exit 1
  fi
  sleep 2
done

ensure_topic() {
  topic=$1
  retention_config=$2

  kafka_command kafka-topics.sh \
    --bootstrap-server "$BOOTSTRAP_SERVER" \
    --create \
    --if-not-exists \
    --topic "$topic" \
    --partitions 3 \
    --replication-factor 1

  kafka_command kafka-configs.sh \
    --bootstrap-server "$BOOTSTRAP_SERVER" \
    --alter \
    --entity-type topics \
    --entity-name "$topic" \
    --add-config "$retention_config"

  topic_description="$(kafka_command kafka-topics.sh \
    --bootstrap-server "$BOOTSTRAP_SERVER" \
    --describe \
    --topic "$topic")"
  topic_partitions="$(printf '%s\n' "$topic_description" | awk '
    {
      for (field = 1; field <= NF; field++) {
        if ($field == "PartitionCount:") { print $(field + 1); exit }
      }
    }
  ')"
  topic_replication_factor="$(printf '%s\n' "$topic_description" | awk '
    {
      for (field = 1; field <= NF; field++) {
        if ($field == "ReplicationFactor:") { print $(field + 1); exit }
      }
    }
  ')"
  [ "$topic_partitions" = '3' ] || {
    echo "Kafka topic does not have exactly three partitions: $topic" >&2
    exit 1
  }
  [ "$topic_replication_factor" = '1' ] || {
    echo "Kafka topic does not have replication factor one: $topic" >&2
    exit 1
  }

  config_description="$(kafka_command kafka-configs.sh \
    --bootstrap-server "$BOOTSTRAP_SERVER" \
    --describe \
    --entity-type topics \
    --entity-name "$topic")"
  retention_ms="${retention_config%%,*}"
  retention_bytes="${retention_config#*,}"
  printf '%s\n' "$config_description" | grep -F "$retention_ms sensitive=false" >/dev/null || {
    echo "Kafka topic retention.ms is incorrect: $topic" >&2
    exit 1
  }
  printf '%s\n' "$config_description" | grep -F "$retention_bytes sensitive=false" >/dev/null || {
    echo "Kafka topic retention.bytes is incorrect: $topic" >&2
    exit 1
  }
}

ensure_topic novel-book-visit-v1 "$MAIN_RETENTION"
ensure_topic novel-book-visit-dlt "$DLT_RETENTION"
ensure_topic novel-reading-engagement-v1 "$MAIN_RETENTION"
ensure_topic novel-reading-engagement-dlt "$DLT_RETENTION"

echo 'Kafka topic creation and retention verification completed successfully.'
