# 可复现的本地开发环境（Windows / PowerShell）

本地基础设施由 `compose.local.yml` 定义。Java 21、Maven、Node 和 Docker Desktop
需在本机可用；`mvn`、`node` 在 PATH 中。以下命令均从 clone 的仓库根目录运行。
不需要 Travel ZIP 或旧电脑数据库，生产配置不参与这套启动流程。

## 配置一次，Compose 与应用共用

首次配置时（已有 `.env` 时不要覆盖）：

```powershell
Copy-Item .env.example .env
```

在 ignored `.env` 中替换三个 `EXAMPLE_ONLY_...` 占位值，并设置 JWT、cache manager、
认证 HMAC、阅读 IP HMAC 四个必需 secret。示例值仅供 Compose 解析检查，不能直接用于启动。
`deploy/scripts/start-local-front.ps1` 不打印 secret，不把 secret 写入文件或命令行参数。

| 环境变量 | 默认宿主机端口 | 容器端口 | Windows 上的应用连接 |
|---|---:|---:|---|
| MYSQL_HOST_PORT | 3307 | 3306 | 127.0.0.1:MYSQL_HOST_PORT |
| REDIS_HOST_PORT | 6380 | 6379 | 127.0.0.1:REDIS_HOST_PORT |
| KAFKA_HOST_PORT | 9092 | 9092 | 127.0.0.1:KAFKA_HOST_PORT |
| MAILPIT_SMTP_HOST_PORT | 1025 | 1025 | 127.0.0.1:MAILPIT_SMTP_HOST_PORT |
| MAILPIT_UI_HOST_PORT | 8025 | 8025 | 浏览器访问宿主机端口 |
| PROMETHEUS_HOST_PORT | 9090 | 9090 | 浏览器访问宿主机端口 |
| GRAFANA_HOST_PORT | 3000 | 3000 | 浏览器访问宿主机端口 |

Kafka 对外 advertised listener 与 `KAFKA_HOST_PORT` 共用同一个值；容器网络内部的
`novel-kafka:19092` 不随宿主机端口改变。Mailpit 与监控的宿主机绑定仍限制为 `127.0.0.1`。
Prometheus 容器与 Grafana 仍使用容器端口 9090，应用指标端口固定为 8084。

Windows 端口有冲突时，先检查监听与保留范围：

```powershell
Get-NetTCPConnection -LocalPort 3307 -ErrorAction SilentlyContinue
netsh interface ipv4 show excludedportrange protocol=tcp
netsh interface ipv6 show excludedportrange protocol=tcp
```

在 `.env` 中覆盖宿主机端口，不修改 Compose 文件。例如本次笔记本使用：

```dotenv
MYSQL_HOST_PORT=13307
REDIS_HOST_PORT=6380
KAFKA_HOST_PORT=19092
MAILPIT_SMTP_HOST_PORT=11025
MAILPIT_UI_HOST_PORT=8025
PROMETHEUS_HOST_PORT=19090
GRAFANA_HOST_PORT=3000
```

helper 支持字面量 `KEY=VALUE`、空行、整行注释和成对单/双引号；不执行 shell、不支持
env-file 插值或多行值。显式进程环境变量优先于 env 文件，与 Compose 的覆盖方向一致。
修改 `.env` 后需重新运行 Compose 和重启应用；运行中进程不会自动重读 `.env`。

## 启动基础设施

```powershell
docker compose --env-file .env.example -f compose.local.yml config --quiet
docker compose --env-file .env -f compose.local.yml config --quiet
docker compose --env-file .env -f compose.local.yml up -d
docker compose --env-file .env -f compose.local.yml ps
```

已有数据库和 topics 时不要再次导入 seed。首次空库初始化使用 Git 中标准脚本：

```powershell
$env:ENV_FILE = '.env'
$env:COMPOSE_FILE = 'compose.local.yml'
$env:MSYS_NO_PATHCONV = '1'
& 'D:\git\Git\bin\bash.exe' deploy/scripts/init-database.sh
& 'D:\git\Git\bin\bash.exe' deploy/scripts/init-kafka-topics.sh
```

Git Bash 安装路径需按本机调整。`MSYS_NO_PATHCONV=1` 防止容器内 `/opt/kafka/...` 被
改为 Windows 路径。数据库脚本在部分 Git Bash/原生 Docker 组合下存在 FIFO stdin 传递
兼容性问题；遇到 `unzip=141 mysql=0` 时先检查数据库状态，不要盲目重跑 seed。若曾失败，
应在确认没有导入业务表后处理失败标记，或在具有正常 FIFO 支持的 shell 中执行原脚本。
已有完整 seed 的数据库只需在有迁移需求时使用脚本的 `--migrate-only`。

## 标准应用启动入口

```powershell
& ./deploy/scripts/start-local-front.ps1 -ValidateOnly
& ./deploy/scripts/start-local-front.ps1
```

helper 根据脚本位置定位仓库，与当前工作目录和 clone 路径无关；优先使用 `JAVA_HOME`
并检查 Java 21，从 PATH 定位 Maven，也支持 `-MavenCommand <完整路径>`。
默认先安装 front 的 Reactor 依赖，跳过测试及会复制模板的 Antrun，再运行 front。
已经完成依赖安装时可用 `-SkipBuild`。`-EnvFile <路径>` 可指定其他本机 env 文件。
8083 或 8084 已被监听时会拒绝启动，不会自动终止其他进程。

模板位于 `deploy/local/`。Spring 配置直接使用端口环境变量；ShardingSphere 使用
`placeholder-type=environment`，helper 仅在内存中把 MySQL 密码转成安全的 YAML 标量。
没有包含 secret 的配置生成步骤。图片/文本目录指向 ignored `deploy/runtime/local/`。
此前的 `deploy/runtime/local/start-front.ps1` 和本机敏感配置可以保留，但不再是标准启动入口。

当前 Java main 未将 `args` 传给 `SpringApplication.run`。因此 helper 用进程环境变量设置
`SPRING_PROFILES_ACTIVE=dev,monitoring` 与 `SPRING_CONFIG_ADDITIONAL_LOCATION`，不依赖会
被丢弃的命令行 args，也不修改 Java 入口或生产脚本。

本地模板保留既有离线配置：SMTP 仅访问 Mailpit，HTTP 代理关闭，未配置的外部 AI 模型为
`none`。需要真实 AI 时设置对应 `NOVEL_AI_MODEL_*` 和 `NOVEL_AI_API_KEY`；本地恢复验收
不覆盖外部 AI、支付、OSS 等服务。没有 Bean 排除列表或自动配置排除列表。

## 验证

```powershell
& ./performance/test-local-development.ps1
mvn '-Dmaven.test.skip=false' test
node --test novel-front/src/test/javascript/*.test.mjs
Invoke-RestMethod http://127.0.0.1:8084/actuator/health
Invoke-WebRequest http://127.0.0.1:8083/
```

健康端点应为 HTTP 200 / UP。Prometheus targets 中
`host.docker.internal:8084/actuator/prometheus` 应为 up。
详情和章节应使用已初始化 seed 中真实、非 VIP、正文存在的记录；非法章节应为 404。
不要 flush Redis、清空 volumes、重复 seed 或使用购买接口来完成这项验证。

基础设施行为测试只执行配置解析和 helper preflight，留下 ignored fixture 文件供检查，
不会启动/停止容器、导入数据库或启动业务应用。
