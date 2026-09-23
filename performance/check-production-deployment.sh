#!/bin/sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.prod}"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT_DIR/compose.prod.yml}"
BACKUP_SCRIPT="$ROOT_DIR/deploy/scripts/backup-mysql.sh"
BACKUP_DIR="$ROOT_DIR/deploy/backups"
BACKUP_MAX_AGE_SECONDS="${BACKUP_MAX_AGE_SECONDS:-86400}"
SSH_PORT="${SSH_PORT:-22}"
SWAP_SAMPLE_SECONDS="${SWAP_SAMPLE_SECONDS:-5}"
PUBLIC_BASE_URL="${PUBLIC_BASE_URL:-http://127.0.0.1}"
ALLOW_BACKUP=false
required_health=service_healthy

case "${1:-}" in
  '') ;;
  --allow-backup) ALLOW_BACKUP=true ;;
  *)
    echo 'Usage: performance/check-production-deployment.sh [--allow-backup]' >&2
    exit 64
    ;;
esac
if [ "$#" -gt 1 ]; then
  echo 'Usage: performance/check-production-deployment.sh [--allow-backup]' >&2
  exit 64
fi
case "$SSH_PORT:$BACKUP_MAX_AGE_SECONDS:$SWAP_SAMPLE_SECONDS" in
  *[!0-9:]*|::*|*::)
    echo 'Runtime checker numeric settings are invalid.' >&2
    exit 64
    ;;
esac
case "$PUBLIC_BASE_URL" in
  http://127.0.0.1|https://127.0.0.1|http://localhost|https://localhost) ;;
  *)
    echo 'PUBLIC_BASE_URL must target localhost; test the public domain separately.' >&2
    exit 64
    ;;
esac

for required_file in "$ENV_FILE" "$COMPOSE_FILE" "$BACKUP_SCRIPT"; do
  if [ ! -f "$required_file" ]; then
    echo "Required production artifact is missing: $(basename "$required_file")" >&2
    exit 66
  fi
done
for command_name in docker ss curl awk grep free df sort stat gzip sha256sum sleep date; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "Required command is missing: $command_name" >&2
    exit 69
  }
done

compose() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

expected_services='grafana kafka mysql nginx novel-front prometheus redis'
actual_services="$(compose ps --services --status running | sort | tr '\n' ' ' | sed 's/ $//')"
if [ "$actual_services" != "$expected_services" ]; then
  echo 'FAIL: the running production service set is incomplete or unexpected.' >&2
  exit 1
fi

for service_name in $expected_services; do
  container_id="$(compose ps -q "$service_name")"
  if [ -z "$container_id" ]; then
    echo "FAIL: service is missing: $service_name" >&2
    exit 1
  fi
  state="$(docker inspect --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' "$container_id")"
  expected_state="running ${required_health#service_}"
  if [ "$state" != "$expected_state" ]; then
    echo "FAIL: service is not healthy: $service_name" >&2
    exit 1
  fi
done
echo 'PASS: all seven production services are running and healthy.'

if ! ss -H -lnt | awk -v ssh_port="$SSH_PORT" '
  {
    endpoint=$4
    port=endpoint
    sub(/^.*:/, "", port)
    gsub(/\]$/, "", port)
    if (endpoint ~ /^127\./ || endpoint ~ /^\[::1\]:/ || endpoint ~ /^::1:/ || endpoint ~ /^localhost:/) next
    if (port == ssh_port) ssh_seen=1
    else if (port == "80") http_seen=1
    else if (port == "443") tls_seen=1
    else unexpected=1
  }
  END { if (!ssh_seen || !http_seen || unexpected) exit 1 }
'; then
  echo 'FAIL: public listening sockets differ from SSH/80 and optional 443.' >&2
  exit 1
fi
echo 'PASS: public listening sockets are restricted to SSH, HTTP, and optional HTTPS.'

home_status="$(curl -sS -o /dev/null -w '%{http_code}' --connect-timeout 3 --max-time 15 "$PUBLIC_BASE_URL/")"
actuator_status="$(curl -sS -o /dev/null -w '%{http_code}' --connect-timeout 3 --max-time 15 "$PUBLIC_BASE_URL/actuator/health")"
if [ "$home_status" != 200 ] || [ "$actuator_status" != 404 ]; then
  echo "FAIL: public HTTP boundary returned home=$home_status actuator=$actuator_status." >&2
  exit 1
fi
echo 'PASS: homepage is available and Actuator is denied by Nginx.'

prometheus_result="$(curl -fsS --get --data-urlencode 'query=up{job="novel-front"}' 'http://127.0.0.1:9090/api/v1/query')"
if ! printf '%s' "$prometheus_result" | grep -Eq '"status":"success".*"result":\[[^]]*"value":\[[^]]*,"1"\]'; then
  echo 'FAIL: Prometheus does not report the novel-front target as UP.' >&2
  exit 1
fi
echo 'PASS: Prometheus reports the novel-front target as UP.'

for consumer_group in novel-book-visit-writer-v1 novel-reading-engagement-writer-v1; do
  group_output="$(compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server 127.0.0.1:19092 --describe --group "$consumer_group" 2>/dev/null)" || {
      echo "FAIL: Kafka consumer lag query failed: $consumer_group" >&2
      exit 1
    }
  group_lag="$(printf '%s\n' "$group_output" | awk '
    $1 != "GROUP" && $6 ~ /^[0-9]+$/ { total += $6; found=1 }
    END { if (!found) exit 1; print total+0 }
  ')" || {
    echo "FAIL: Kafka consumer lag is unavailable: $consumer_group" >&2
    exit 1
  }
  if [ "$group_lag" -eq 0 ]; then
    echo "PASS: Kafka consumer lag is zero: $consumer_group"
  else
    echo "WARN: Kafka consumer lag is nonzero: $consumer_group lag=$group_lag"
  fi
done

for service_name in $expected_services; do
  case "$service_name" in
    nginx) expected_limit=67108864 ;;
    novel-front) expected_limit=939524096 ;;
    mysql) expected_limit=805306368 ;;
    redis) expected_limit=268435456 ;;
    kafka) expected_limit=671088640 ;;
    prometheus) expected_limit=402653184 ;;
    grafana) expected_limit=268435456 ;;
  esac
  container_id="$(compose ps -q "$service_name")"
  actual_limit="$(docker inspect --format '{{.HostConfig.Memory}}' "$container_id")"
  memory_percent="$(docker stats --no-stream --format '{{.MemPerc}}' "$container_id" | tr -d ' %')"
  if [ "$actual_limit" != "$expected_limit" ] ||
     ! awk -v percent="$memory_percent" 'BEGIN { exit !(percent >= 0 && percent < 100) }'; then
    echo "FAIL: container memory boundary is invalid or exhausted: $service_name" >&2
    exit 1
  fi
done
echo 'PASS: container memory limits are active and current use is below each limit.'

memory_available="$(free -b | awk '$1 == "Mem:" {print $7}')"
swap_before="$(free -b | awk '$1 == "Swap:" {print $3}')"
case "$memory_available:$swap_before" in *[!0-9:]*) echo 'FAIL: host memory data is unavailable.' >&2; exit 1;; esac
if [ "$memory_available" -lt 402653184 ]; then
  echo 'FAIL: host physical memory headroom is below 384 MiB.' >&2
  exit 1
fi
sleep "$SWAP_SAMPLE_SECONDS"
swap_after="$(free -b | awk '$1 == "Swap:" {print $3}')"
case "$swap_after" in *[!0-9]*) echo 'FAIL: host swap data is unavailable.' >&2; exit 1;; esac
if [ "$swap_after" -gt "$swap_before" ]; then
  echo 'FAIL: swap use grew during the runtime sample.' >&2
  exit 1
fi
echo 'PASS: host has physical memory headroom and no observed swap growth.'

available_kib="$(df -Pk "$ROOT_DIR" | awk 'NR == 2 {print $4}')"
case "$available_kib" in ''|*[!0-9]*) echo 'FAIL: disk free-space data is unavailable.' >&2; exit 1;; esac
available_bytes=$((available_kib * 1024))
if [ "$available_bytes" -lt 10737418240 ]; then
  echo 'FAIL: host disk has less than 10 GiB free.' >&2
  exit 1
fi
echo 'PASS: host disk has at least 10 GiB free.'

if [ "$ALLOW_BACKUP" = true ]; then
  "$BACKUP_SCRIPT" >/dev/null
fi
if [ ! -d "$BACKUP_DIR" ]; then
  echo 'FAIL: no production backup directory exists.' >&2
  exit 1
fi
latest_backup="$(
  for candidate in "$BACKUP_DIR/"*.sql.gz; do
    [ -f "$candidate" ] && [ ! -L "$candidate" ] && basename "$candidate"
  done | sort -r | awk 'NR == 1'
)"
if [ -z "$latest_backup" ] || [ ! -f "$BACKUP_DIR/$latest_backup.sha256" ]; then
  echo 'FAIL: no complete MySQL backup pair exists.' >&2
  exit 1
fi
gzip -t "$BACKUP_DIR/$latest_backup"
checksum_line="$(cat "$BACKUP_DIR/$latest_backup.sha256")"
set -- $checksum_line
if [ "$#" -ne 2 ] || [ "$2" != "$latest_backup" ]; then
  echo 'FAIL: latest backup SHA-256 companion file is invalid.' >&2
  exit 1
fi
expected_checksum=$1
actual_checksum_output="$(sha256sum "$BACKUP_DIR/$latest_backup")"
actual_checksum="${actual_checksum_output%% *}"
if [ "$expected_checksum" != "$actual_checksum" ]; then
  echo 'FAIL: latest backup SHA-256 verification failed.' >&2
  exit 1
fi
backup_age=$(( $(date +%s) - $(stat -c %Y "$BACKUP_DIR/$latest_backup") ))
if [ "$backup_age" -lt 0 ] || [ "$backup_age" -gt "$BACKUP_MAX_AGE_SECONDS" ]; then
  echo 'FAIL: latest database backup is not fresh.' >&2
  exit 1
fi
backup_size="$(stat -c %s "$BACKUP_DIR/$latest_backup")"
printf 'PASS: fresh database backup verified: %s bytes=%s\n' "$latest_backup" "$backup_size"
echo 'Production deployment runtime verification passed.'
