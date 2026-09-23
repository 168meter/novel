#!/bin/sh
set -eu
umask 077
: "${MYSQL_DATABASE:?MYSQL_DATABASE is required}"
: "${MYSQL_USER:?MYSQL_USER is required}"
: "${MYSQL_PASSWORD:?MYSQL_PASSWORD is required}"
: "${REDIS_PASSWORD:?REDIS_PASSWORD is required}"
: "${JWT_SECRET:?JWT_SECRET is required}"
: "${CACHE_MANAGER_PASSWORD:?CACHE_MANAGER_PASSWORD is required}"
: "${NOVEL_AUTH_HMAC_SECRET:?NOVEL_AUTH_HMAC_SECRET is required}"
: "${NOVEL_READING_ENGAGEMENT_IP_HMAC_SECRET:?NOVEL_READING_ENGAGEMENT_IP_HMAC_SECRET is required}"
: "${MAIL_USERNAME:?MAIL_USERNAME is required}"
: "${MAIL_PASSWORD:?MAIL_PASSWORD is required}"

case "$MYSQL_DATABASE" in
  *[!A-Za-z0-9_]*)
    echo 'MYSQL_DATABASE may contain only letters, digits, and underscores.' >&2
    exit 64
    ;;
esac

yaml_quote() {
  value=$1
  carriage_return="$(printf '\r')"
  newline='
'
  case "$value" in
    *"$carriage_return"*|*"$newline"*)
      echo 'MySQL credentials must not contain line breaks.' >&2
      exit 64
      ;;
  esac
  escaped="$(printf '%s' "$value" | sed "s/'/''/g")"
  printf "'%s'" "$escaped"
}

MYSQL_USER_YAML="$(yaml_quote "$MYSQL_USER")"
MYSQL_PASSWORD_YAML="$(yaml_quote "$MYSQL_PASSWORD")"
export MYSQL_USER_YAML MYSQL_PASSWORD_YAML
mkdir -p /app/runtime /app/logs
envsubst '${MYSQL_DATABASE} ${MYSQL_USER_YAML} ${MYSQL_PASSWORD_YAML}' \
  < /app/config/shardingsphere-jdbc.yml.template \
  > /app/runtime/shardingsphere-jdbc.yml
chmod 600 /app/runtime/shardingsphere-jdbc.yml
export SPRING_DATASOURCE_URL='jdbc:shardingsphere:absolutepath:/app/runtime/shardingsphere-jdbc.yml'
exec java -jar /app/novel-front.jar
