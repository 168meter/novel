#!/bin/sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.prod}"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT_DIR/compose.prod.yml}"
SEED_ARCHIVE="$ROOT_DIR/doc/sql/novel_plus_data.sql.zip"
READING_MIGRATION="$ROOT_DIR/doc/sql/20260911_reading_daily_aggregation.sql"
AUTH_MIGRATION="$ROOT_DIR/doc/sql/20260917_authentication_security.sql"
EXPECTED_BASE_TABLE_COUNT=50
MIGRATE_ONLY=false

case "${1:-}" in
  '') ;;
  --migrate-only) MIGRATE_ONLY=true ;;
  *)
    echo 'Usage: deploy/scripts/init-database.sh [--migrate-only]' >&2
    exit 64
    ;;
esac
if [ "$#" -gt 1 ]; then
  echo 'Usage: deploy/scripts/init-database.sh [--migrate-only]' >&2
  exit 64
fi

for required_file in "$ENV_FILE" "$COMPOSE_FILE" "$SEED_ARCHIVE" "$READING_MIGRATION" "$AUTH_MIGRATION"; do
  if [ ! -f "$required_file" ]; then
    echo "Required file is missing: $required_file" >&2
    exit 66
  fi
done
command -v docker >/dev/null 2>&1 || {
  echo 'docker is required.' >&2
  exit 69
}
command -v unzip >/dev/null 2>&1 || {
  echo 'unzip is required.' >&2
  exit 69
}

compose() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

mysql_query() {
  query=$1
  compose exec -T mysql sh -eu -c '
    export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
    exec mysql --default-character-set=utf8mb4 --batch --skip-column-names \
      --user=root --database="$MYSQL_DATABASE" --execute="$1"
  ' sh "$query"
}

mysql_scalar() {
  query_output="$(mysql_query "$1")" || return $?
  printf '%s' "$query_output" | tr -d '\r'
}

validate_count() {
  count_name=$1
  count_value=$2
  case "$count_value" in
    ''|*[!0-9]*)
      echo "Invalid numeric count for $count_name: $count_value" >&2
      exit 1
      ;;
  esac
}

attempt=0
until compose exec -T mysql sh -eu -c '
  export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
  mysqladmin ping --host=127.0.0.1 --user=root --silent >/dev/null
'; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 60 ]; then
    echo 'MySQL did not become ready within 120 seconds.' >&2
    exit 1
  fi
  sleep 2
done

database_name="$(compose exec -T mysql sh -eu -c 'printf %s "$MYSQL_DATABASE"' | tr -d '\r')"
table_count="$(mysql_scalar 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()')"
book_table_count="$(mysql_scalar "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'book'")"
base_table_count="$(mysql_scalar "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('author','author_code','author_income','author_income_detail','book','book_author','book_category','book_comment','book_comment_reply','book_content','book_content0','book_content1','book_content2','book_content3','book_content4','book_content5','book_content6','book_content7','book_content8','book_content9','book_index','book_screen_bullet','book_setting','crawl_batch_task','crawl_single_task','crawl_source','friend_link','news','news_category','order_pay','sys_data_perm','sys_dept','sys_dict','sys_file','sys_gen_columns','sys_gen_table','sys_gen_table_column','sys_log','sys_menu','sys_role','sys_role_data_perm','sys_role_menu','sys_user','sys_user_role','user','user_bookshelf','user_buy_record','user_feedback','user_read_history','website_info')")"
state_table_count="$(mysql_scalar "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = '_novel_deployment_state'")"
validate_count table_count "$table_count"
validate_count book_table_count "$book_table_count"
validate_count base_table_count "$base_table_count"
validate_count state_table_count "$state_table_count"

deployment_state=''
if [ "$state_table_count" -eq 1 ]; then
  deployment_state="$(mysql_scalar "SELECT state FROM _novel_deployment_state WHERE component = 'base_seed_v1'")"
fi

create_state_table() {
  mysql_query "CREATE TABLE IF NOT EXISTS _novel_deployment_state (component varchar(64) NOT NULL PRIMARY KEY, state varchar(16) NOT NULL, updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
}

mark_seed_state() {
  state=$1
  mysql_query "INSERT INTO _novel_deployment_state (component, state) VALUES ('base_seed_v1', '$state') ON DUPLICATE KEY UPDATE state='$state'"
}

cleanup_import() {
  if [ -n "${IMPORT_FIFO:-}" ]; then rm -f "$IMPORT_FIFO"; fi
  if [ -n "${IMPORT_DIR:-}" ] && [ -d "$IMPORT_DIR" ]; then rmdir "$IMPORT_DIR" 2>/dev/null || true; fi
}

import_seed() {
  IMPORT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/novel-seed.XXXXXX")"
  IMPORT_FIFO="$IMPORT_DIR/seed.sql"
  mkfifo "$IMPORT_FIFO"
  trap 'cleanup_import; exit 130' HUP INT TERM

  unzip -p "$SEED_ARCHIVE" novel_plus_data.sql > "$IMPORT_FIFO" &
  unzip_pid=$!
  mysql_status=0
  compose exec -T mysql sh -eu -c '
    export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
    exec mysql --default-character-set=utf8mb4 --batch --user=root
  ' < "$IMPORT_FIFO" || mysql_status=$?
  unzip_status=0
  wait "$unzip_pid" || unzip_status=$?
  cleanup_import
  IMPORT_FIFO=''
  IMPORT_DIR=''
  trap - HUP INT TERM

  if [ "$unzip_status" -ne 0 ] || [ "$mysql_status" -ne 0 ]; then
    echo "Seed import failed: unzip=$unzip_status mysql=$mysql_status" >&2
    return 1
  fi
}

if [ "$table_count" -eq 0 ]; then
    if [ "$MIGRATE_ONLY" = true ]; then
      echo 'Database is empty; initialize the base schema before using --migrate-only.' >&2
      exit 1
    fi
    if [ "$database_name" != 'novel_plus' ]; then
      echo 'The bundled seed archive requires MYSQL_DATABASE=novel_plus.' >&2
      exit 1
    fi
    unzip -t "$SEED_ARCHIVE" >/dev/null
    create_state_table
    mark_seed_state loading
    import_seed
    base_table_count="$(mysql_scalar "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('author','author_code','author_income','author_income_detail','book','book_author','book_category','book_comment','book_comment_reply','book_content','book_content0','book_content1','book_content2','book_content3','book_content4','book_content5','book_content6','book_content7','book_content8','book_content9','book_index','book_screen_bullet','book_setting','crawl_batch_task','crawl_single_task','crawl_source','friend_link','news','news_category','order_pay','sys_data_perm','sys_dept','sys_dict','sys_file','sys_gen_columns','sys_gen_table','sys_gen_table_column','sys_log','sys_menu','sys_role','sys_role_data_perm','sys_role_menu','sys_user','sys_user_role','user','user_bookshelf','user_buy_record','user_feedback','user_read_history','website_info')")"
    validate_count base_table_count "$base_table_count"
    if [ "$base_table_count" -ne "$EXPECTED_BASE_TABLE_COUNT" ]; then
      echo "Seed import is incomplete: expected $EXPECTED_BASE_TABLE_COUNT base tables, found $base_table_count." >&2
      exit 1
    fi
    mark_seed_state ready
elif [ "$deployment_state" = 'loading' ]; then
    echo 'A previous seed import was interrupted; restore or recreate the database before retrying.' >&2
    exit 1
elif [ "$book_table_count" -ne 1 ] || [ "$base_table_count" -ne "$EXPECTED_BASE_TABLE_COUNT" ]; then
    echo 'Database does not contain the complete expected base schema.' >&2
    exit 1
else
    if [ "$MIGRATE_ONLY" != true ]; then
      echo 'Database is not empty; rerun with --migrate-only.' >&2
      exit 1
    fi
    if [ -n "$deployment_state" ] && [ "$deployment_state" != 'ready' ]; then
      echo "Unexpected base seed state: $deployment_state" >&2
      exit 1
    fi
    if [ -z "$deployment_state" ]; then
      create_state_table
      mark_seed_state ready
    fi
fi

apply_migration() {
  migration_file=$1
  compose exec -T mysql sh -eu -c '
    export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
    exec mysql --default-character-set=utf8mb4 --batch \
      --user=root --database="$MYSQL_DATABASE"
  ' < "$migration_file"
}

apply_migration "$READING_MIGRATION"
apply_migration "$AUTH_MIGRATION"
echo 'Database initialization and migrations completed successfully.'
