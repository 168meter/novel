# Novel-Plus 2C4G 生产部署手册

本手册面向一台香港 Ubuntu 24.04、2 vCPU、4 GiB RAM、约 50 GiB 磁盘的短期展示服务器。
目标是以最小公网暴露面运行 Nginx、novel-front、MySQL、Redis、Kafka、Prometheus 和
Grafana。Docker Compose plugin 负责单机编排；不引入 Kubernetes、ELK、注册中心或网关集群。

## 1. 最终拓扑与边界

```text
Internet
   |
   | 80，HTTPS 激活后增加 443
   v
Nginx (edge: 172.30.0.2)
   |
   v
novel-front :8083 / Actuator :8084
   |                 |
   | backend         | monitoring
   v                 v
MySQL Redis Kafka   Prometheus -> Grafana
```

- 公网只发布 Nginx。`novel-front`、8084 Actuator、MySQL、Redis、Kafka 均无宿主机端口。
- Prometheus `9090` 和 Grafana `3000` 只绑定 `127.0.0.1`，通过 SSH tunnel 查看。
- `backend` 与 `monitoring` 是 Docker internal network；Nginx 不加入这两个网络。
- Docker 发布端口可能绕过部分 UFW 规则，因此云平台安全组仍是第一道边界；仓库的生产
  Compose 本身不发布任何中间件端口。
- HTTP-only 准备期不得向公众开放真实登录、注册或密码。安全组先把 80 限制到维护者 IP；
  ACME standalone 签发时停止 Nginx，证书就绪并启用 HTTPS 后才向公众开放认证功能。

### 资源上限

| 服务 | 容器内存上限 | 主要内部限制 |
| --- | ---: | --- |
| Nginx | 64 MiB | 2 workers、请求/连接限流 |
| novel-front | 896 MiB | JVM Xmx 512 MiB、Metaspace 160 MiB |
| MySQL | 768 MiB | InnoDB buffer pool 384 MiB、60 connections |
| Redis | 256 MiB | maxmemory 176 MiB、allkeys-lfu |
| Kafka | 640 MiB | heap 384 MiB、单 broker |
| Prometheus | 384 MiB | 7 天或 1 GiB retention |
| Grafana | 256 MiB | 禁止匿名访问和自动安装插件 |

上限合计约 3.2 GiB，余量留给 Linux、Docker、Page Cache 和峰值波动。4 GiB Swap 只用于
OOM 保护，不能作为正常容量。2C4G 的压测结果是 environment-specific，不是通用容量承诺。

## 2. Ubuntu 24.04 初始化

以下命令由拥有 sudo 权限的初始账号执行。先创建 non-root 部署账号，并只使用 SSH key：

```bash
sudo adduser deploy
sudo usermod -aG sudo deploy
sudo install -d -m 700 -o deploy -g deploy /home/deploy/.ssh
sudoedit /home/deploy/.ssh/authorized_keys
sudo chown deploy:deploy /home/deploy/.ssh/authorized_keys
sudo chmod 600 /home/deploy/.ssh/authorized_keys
```

确认密钥登录成功后，新建 `/etc/ssh/sshd_config.d/99-novel-hardening.conf`：

```text
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
PermitRootLogin no
```

```bash
sudo sshd -t
sudo systemctl reload ssh
```

不要在验证新会话前关闭当前 SSH 连接。

### Docker Engine、工具和时区

Docker Engine 使用 Docker 官方 apt 仓库，不使用 convenience script：

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl unzip ufw openssl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
sudo tee /etc/apt/sources.list.d/docker.sources >/dev/null <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker deploy
sudo systemctl enable --now docker
sudo timedatectl set-timezone Asia/Shanghai
docker --version
docker compose version
```

`docker` 组近似 root 权限，只把受信任的 deploy 用户加入该组，之后重新登录使组权限生效。

### 4 GiB Swap

先运行 `swapon --show`；仅在没有现有 Swap 时执行：

```bash
sudo fallocate -l 4G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-novel-plus.conf
sudo sysctl --system
free -h
```

### 防火墙和云安全组

SSH 只允许维护者固定 IP 更安全；下面的 UFW 是主机基线，云安全组也必须同步配置：

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw enable
sudo ufw status verbose
```

HTTPS listener 与 certificate 就绪前不要开放 443。绝不能添加 MySQL、Redis、Kafka、
Spring Boot、Actuator、Prometheus 或 Grafana 的公网规则。

## 3. 获取项目和创建生产环境文件

```bash
sudo install -d -m 750 -o deploy -g deploy /opt/novel-plus
cd /opt/novel-plus
# 使用 Git clone、私有制品或校验过的归档把当前提交放到这里
cp .env.prod.example .env.prod
chmod 600 .env.prod
```

逐项编辑 `.env.prod`。每个密码/HMAC/JWT 都必须独立生成，不能复用：

```bash
openssl rand -hex 32
openssl rand -hex 32
openssl rand -hex 32
```

- `MYSQL_USER` 保持独立应用账号 `novel_app`，不能使用 root。
- MySQL root、应用密码、Redis、Grafana、JWT、缓存管理、认证 HMAC、阅读 IP HMAC 均使用
  不同随机值；SMTP 使用 163 授权码而非邮箱登录密码。
- `.env.prod` 不进入 Git、聊天、截图、Shell history 或工单。
- 对历史上曾提交或分享过的 SMTP、Alipay、OSS 等凭据执行 provider-side credential rotation；
  只从仓库删掉明文不能让旧凭据失效。
- 上线前完成 copyright 与数据授权检查。原始小说、封面、数据库样例或爬取内容不当然具备
  公网传播权；删除无授权内容，只保留自有、明确许可或合规演示数据，并准备隐私说明。

渲染检查只验证结构，不输出结果到日志或聊天：

```bash
docker compose --env-file .env.prod -f compose.prod.yml config --quiet
```

不要运行会打印完整渲染配置的 `docker compose config`，其中可能包含敏感环境变量。

## 4. 第一次启动顺序

先构建，不直接启动全部服务：

```bash
docker compose --env-file .env.prod -f compose.prod.yml build
docker compose --env-file .env.prod -f compose.prod.yml up -d mysql redis kafka
docker compose --env-file .env.prod -f compose.prod.yml ps
```

初始化数据库与固定 Kafka topics：

```bash
./deploy/scripts/init-database.sh
./deploy/scripts/init-kafka-topics.sh
```

若迁移的是已有数据库，不导入样例库；把最终 SQL 备份放入 `deploy/backups`，使用恢复脚本，
再以 `./deploy/scripts/init-database.sh --migrate-only` 校验/补充迁移。

启动私有应用和监控，先不要启动 Nginx：

```bash
docker compose --env-file .env.prod -f compose.prod.yml up -d novel-front prometheus grafana
docker compose --env-file .env.prod -f compose.prod.yml ps
docker compose --env-file .env.prod -f compose.prod.yml logs --tail=200 novel-front
curl -fsS http://127.0.0.1:9090/-/ready
curl -fsS http://127.0.0.1:3000/api/health
```

确认内部服务健康后再启动 Nginx：

```bash
docker compose --env-file .env.prod -f compose.prod.yml up -d nginx
curl -I http://127.0.0.1/
curl -o /dev/null -sS -w '%{http_code}\n' http://127.0.0.1/actuator/health
./performance/check-production-deployment.sh --allow-backup
```

Actuator 经 Nginx 必须返回 404。首次检查用 `--allow-backup` 创建第一份备份，随后默认使用只读：

```bash
./performance/check-production-deployment.sh
```

### first-launch acceptance

- 七个容器均为 healthy，且 `docker stats --no-stream` 没有接近内存上限。
- 宿主机公开监听只有 SSH、80，以及 HTTPS 激活后的 443。
- 首页 200、Nginx 下 `/actuator/health` 为 404、Prometheus target 为 UP。
- 两个 Kafka consumer lag 为 0；若非 0，等待 drain 后复查。
- `free -h` 有明显物理内存余量，Swap 不持续增长；磁盘至少剩余 10 GiB。
- MySQL 首备份已复制到服务器外并核对 SHA-256。
- 在隔离数据库/卷完成一次恢复演练，不能拿在线库做破坏性恢复测试。
- HTTPS 未激活前，不允许真实用户注册、登录或提交密码。

## 5. 日常操作

正常启动、停止与状态：

```bash
docker compose --env-file .env.prod -f compose.prod.yml up -d
docker compose --env-file .env.prod -f compose.prod.yml down
docker compose --env-file .env.prod -f compose.prod.yml ps
```

`down` 不删除命名卷；生产环境禁止使用 `down -v`。查看日志和资源：

```bash
docker compose --env-file .env.prod -f compose.prod.yml logs -f --tail=200 novel-front
docker compose --env-file .env.prod -f compose.prod.yml logs -f --tail=200 nginx
docker stats --no-stream
df -h
free -h
```

所有容器使用 `json-file` 10 MiB × 3 轮转；应用日志 7 天且总计 256 MiB；Nginx 输出到
Docker 日志；Kafka、Prometheus 均有 retention。磁盘异常增长时先定位，不能直接清空卷。

### 监控 SSH tunnels

在自己的电脑执行，服务器安全组不开放 3000/9090：

```bash
ssh -L 3000:127.0.0.1:3000 deploy@SERVER_IP
ssh -L 9090:127.0.0.1:9090 deploy@SERVER_IP
```

随后本机访问 `http://127.0.0.1:3000` 和 `http://127.0.0.1:9090`。

### 备份与恢复

```bash
./deploy/scripts/backup-mysql.sh
ls -lh deploy/backups/*.sql.gz deploy/backups/*.sha256
./deploy/scripts/restore-mysql.sh deploy/backups/novel_plus-YYYYmmdd-HHMMSS.sql.gz --confirm-restore
```

备份脚本保留最新 7 对 SQL gzip/SHA-256。恢复不会自动 DROP database，但会把 SQL 写入目标库；
执行前必须停写、确认目标环境并另做当前库备份。定时任务只保存到本机还不算备份，必须把 SQL、
图片卷和正文卷加密复制到另一位置，并定期做隔离恢复演练。

## 6. HTTPS 激活

1. 先把 DNS A 记录指向服务器，等待解析稳定；HTTP-only 阶段仍限制真实认证。
2. 停止 Nginx，使用 Certbot standalone 临时占用 80 获取 certificate：

```bash
docker compose --env-file .env.prod -f compose.prod.yml stop nginx
sudo apt-get install -y certbot
sudo certbot certonly --standalone -d YOUR_DOMAIN
```

3. 把 `deploy/nginx/https-activation.example.conf` 复制到 `deploy/nginx/conf.d/`，替换
   `YOUR_DOMAIN`；把现有 HTTP server 改为只处理 ACME/重定向到 HTTPS。
4. 在 Nginx 服务中只读挂载 `/etc/letsencrypt:/etc/letsencrypt:ro`，并与 TLS listener 同一次
   修改加入 `443:443`。证书和 listener 缺一不可，不能先发布空的 443。
5. 验证配置、启动容器，再开放防火墙与云安全组：

```bash
docker compose --env-file .env.prod -f compose.prod.yml run --rm --no-deps nginx nginx -t
docker compose --env-file .env.prod -f compose.prod.yml up -d nginx
sudo ufw allow 443/tcp
curl -I https://YOUR_DOMAIN/
```

6. 验证 HTTP 跳转、TLS、`X-Forwarded-Proto=https`、Secure/HttpOnly/SameSite cookies，重新执行
   runtime 和认证 smoke。只有这些检查通过后，才允许公众真实注册、登录或输入密码。
7. 配置证书自动续期，并用 `certbot renew --dry-run` 验证；续期后 reload Nginx。

## 7. 更新和应用回滚

更新前先确认 consumer lag 为 0 并执行备份。记录当前 Git commit 与镜像 ID：

```bash
git rev-parse HEAD
docker image inspect novel-front:prod --format '{{.Id}}'
./deploy/scripts/backup-mysql.sh
docker compose --env-file .env.prod -f compose.prod.yml build novel-front
docker compose --env-file .env.prod -f compose.prod.yml up -d novel-front nginx
./performance/check-production-deployment.sh
```

应用回滚时签出已验证提交、重新构建并只替换应用/Nginx。数据库迁移必须向后兼容；如果必须恢复
数据库，先停写并使用显式恢复脚本，不能自动 DROP，也不能在仍有新写入时覆盖。任何时候都不要
用 `git reset --hard` 或 `docker compose down -v` 当作回滚方案。

## 8. 迁移到另一台服务器

迁移顺序：

1. 降低 DNS TTL，保持旧服务器运行；新服务器完成第 2 节初始化。
2. 进入维护/停写窗口，等待点击与阅读两个 consumer lag 归零。
3. 创建 final backup，验证 gzip/SHA-256，并复制到新服务器的 `deploy/backups`。
4. 复制仓库同一 commit；通过安全渠道单独复制 `.env.prod`，随后再次 `chmod 600`。
5. 备份并迁移 `novel-front-pictures` 与 `novel-front-books` 命名卷；若已启用 HTTPS，也迁移或
   重新签发证书。MySQL is authoritative；consumer lag 归零后 Redis and Kafka 卷不需要迁移，
   它们在新机重建即可。
6. 新机先启动 MySQL/Redis/Kafka，恢复 SQL，初始化 Kafka topics，再启动 front、监控和 Nginx。
7. 运行 first-launch acceptance、业务 smoke 和隔离恢复演练，然后执行 DNS switch。
8. 保留旧服务器但停止写入。观察稳定后再下线；不要立即删除旧数据。

图片和正文卷可在停写后打包（先用 `docker volume ls` 核对实际卷名）：

```bash
docker run --rm -v novel-plus-prod_novel-front-pictures:/source:ro -v "$PWD/deploy/backups:/backup" redis:7.4.7-alpine sh -c 'cd /source && tar -czf /backup/pictures.tar.gz .'
docker run --rm -v novel-plus-prod_novel-front-books:/source:ro -v "$PWD/deploy/backups:/backup" redis:7.4.7-alpine sh -c 'cd /source && tar -czf /backup/books.tar.gz .'
```

如果 DNS switch 后需要 rollback：先让新服务器停止接收写入，再把 DNS 切回仍处于只读/一致状态
的旧服务器。若新机已经产生业务写入，必须先评估并同步增量，不能直接切回旧快照造成数据丢失。

## 9. 故障处理底线

- 内存不足：先看 `docker stats`、`free`、OOM 日志；不能用扩大 Swap 掩盖持续超配。
- 磁盘不足：检查 Docker 日志、Kafka、Prometheus、应用日志和备份；不要对未知目录递归删除。
- MySQL/Redis/Kafka 不健康：保持 Nginx 错误页或维护状态，先恢复依赖，禁止临时发布其端口。
- 凭据泄露：立即在提供商和数据库执行 credential rotation，再更新 `.env.prod` 并滚动重启。
- 任何破坏性恢复前先保留现场、验证目标绝对路径和备份 checksum。

Docker 安装命令依据 Docker 官方 Ubuntu 文档维护；正式执行前应再次对照官方说明与云厂商安全组
行为，尤其注意 Docker published ports 与 UFW 的交互。
