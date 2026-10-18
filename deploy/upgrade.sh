#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

usage() {
    printf '用法：%s [Docker Compose 全局参数...]\n' "$0"
    printf '始终先拉取镜像，只升级 springagent 和 nginx；源码构建请自行 docker build，本脚本不支持 --build。\n'
}

compose_args=()
for arg in "$@"; do
    case "$arg" in
        --build)
            printf '不支持源码构建：本脚本只从已构建镜像升级。请先自行 docker build，再运行本脚本。\n' >&2
            exit 2
            ;;
        --pull|--pull=*)
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *) compose_args+=("$arg") ;;
    esac
done

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
cd -- "$repo_root"
compose() {
    docker compose "${compose_args[@]}" "$@"
}

project=springagent
database=springagent

compose config -q
compose pull

config_summary=$(compose config --format json | compose run --rm -T --no-deps --pull never --entrypoint python springagent -c '
import json
import sys

config = json.load(sys.stdin)
services = (config.get("services") or {})
rabbitmq = services.get("rabbitmq") or {}
app = services.get("springagent") or {}
hostname = rabbitmq.get("hostname")
volume_type = next(
    (item.get("type") for item in app.get("volumes") or [] if str(item.get("target", "")).rstrip("/") == "/data/cloud"),
    None,
)
if not hostname or not volume_type:
    raise SystemExit("无法从渲染配置读取 RabbitMQ hostname 或 springagent /data/cloud 卷类型。")
print("project=" + (config.get("name") or ""))
print("rabbitmq_hostname=" + hostname)
print("app_data_type=" + volume_type)
')
rabbitmq_hostname=
app_data_type=
project_name=
config_summary=${config_summary//$'\r'/}
while IFS='=' read -r key value; do
    case "$key" in
        project) project_name=$value ;;
        rabbitmq_hostname) rabbitmq_hostname=$value ;;
        app_data_type) app_data_type=$value ;;
    esac
done <<<"$config_summary"

if [[ -z $project_name || -z $rabbitmq_hostname || -z $app_data_type ]]; then
    printf '无法从渲染配置读取项目名、RabbitMQ hostname 或 springagent /data/cloud 卷类型。\n' >&2
    exit 1
fi
if [[ $project_name != "$project" ]]; then
    printf 'Compose 项目名为 %s，预期为 %s。顶层 name 变更会使命名卷前缀从 %s_ 变为 %s_；脚本不会隐式导入旧项目的数据。请手工迁移数据卷后使用 name: %s。\n' \
        "$project_name" "$project" "$project_name" "$project" "$project" >&2
    exit 1
fi
if [[ $app_data_type != volume ]]; then
    printf 'springagent /data/cloud 仍使用宿主机 bind 挂载，本脚本只支持命名卷 app-data。请先迁移数据并改为命名卷。\n' >&2
    exit 1
fi

for container in $(docker ps -a -q \
    --filter "label=com.docker.compose.project=$project" \
    --filter "label=com.docker.compose.service=rabbitmq"); do
    current_hostname=$(docker inspect --format '{{.Config.Hostname}}' "$container")
    if [[ $current_hostname != "$rabbitmq_hostname" ]]; then
        printf 'RabbitMQ 节点名称不一致：现有容器为 %s，配置为 %s。请保持原节点名称或先迁移节点；自动更换节点会丢队列。\n' "$current_hostname" "$rabbitmq_hostname" >&2
        exit 1
    fi
done

for service in springagent nginx; do
    for container in $(docker ps -a -q \
        --filter "label=com.docker.compose.project=$project" \
        --filter "label=com.docker.compose.service=$service"); do
        mount_types=$(docker inspect --format '{{range .Mounts}}{{if or (eq .Destination "/data/cloud") (eq .Destination "/data/cloud/works")}}{{println .Type}}{{end}}{{end}}' "$container")
        if grep -qx 'bind' <<<"$mount_types"; then
            printf '服务 %s 的现有容器仍将 /data/cloud 以宿主机 bind 挂载，当前配置使用命名卷。请先迁移数据；脚本不会自动切换挂载。\n' "$service" >&2
            exit 1
        fi
    done
done

backup_root=${BACKUP_ROOT:-"$repo_root/backups"}
mkdir -p -- "$backup_root"
backup_root=$(cd -- "$backup_root" && pwd -P)
backup_dir=$(mktemp -d "$backup_root/$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX")

maintenance=0
finish() {
    status=$?
    trap - EXIT
    if ((status != 0 && maintenance)); then
        compose stop springagent nginx || true
        printf '升级失败，应用保持停止；未回滚数据库、未删除卷。\n' >&2
    fi
    if [[ -n $backup_dir ]]; then
        printf '备份目录：%s\n' "$backup_dir" >&2
    fi
    exit "$status"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

maintenance=1
compose stop springagent nginx
compose up -d --no-build --pull never --wait --wait-timeout 180 mysql redis rabbitmq

compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --routines --triggers --events --set-gtid-purged=OFF --databases '"$database" > "$backup_dir/mysql.sql.tmp"
mv -- "$backup_dir/mysql.sql.tmp" "$backup_dir/mysql.sql"
compose run --rm -T --no-deps --pull never --entrypoint python springagent -c 'import sys, tarfile; archive = tarfile.open(fileobj=sys.stdout.buffer, mode="w|gz"); archive.add("/data/cloud", arcname="."); archive.close()' > "$backup_dir/app-data.tar.gz.tmp"
mv -- "$backup_dir/app-data.tar.gz.tmp" "$backup_dir/app-data.tar.gz"
compose run --rm -T --no-deps --pull never --entrypoint python springagent -c 'import os, sys, tarfile; archive = tarfile.open(fileobj=sys.stdout.buffer, mode="w|gz"); path = "/data/cloud/.secrets"; os.path.isdir(path) and archive.add(path, arcname=".secrets"); archive.close()' > "$backup_dir/secrets.tar.gz.tmp"
mv -- "$backup_dir/secrets.tar.gz.tmp" "$backup_dir/secrets.tar.gz"

compose up -d --no-deps --no-build --pull never --force-recreate --wait --wait-timeout 300 springagent
compose up -d --no-deps --no-build --pull never --wait --wait-timeout 300 nginx
maintenance=0
printf '升级完成，应用健康检查已通过。\n'
