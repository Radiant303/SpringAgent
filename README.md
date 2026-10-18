# SpringAgent

## Docker 部署

- Linux，已安装并启动 Docker，当前用户具有 Docker 操作权限。
- Docker Compose 插件 **2.23.1 或以上**。
- 已安装 Bash 和 curl，服务器可以访问 GitHub Raw、GHCR 和 Docker Hub。

检查 Docker 和 Compose：

```bash
docker version
docker compose version
```

### 首次部署

**1. 创建部署目录，下载配置。**

```bash
umask 077
mkdir -p ~/springagent && cd ~/springagent &&
curl -fL \
  https://raw.githubusercontent.com/Radiant303/spring-harness/master/docker-compose.yml \
  -o docker-compose.yml
```

**2. 按需修改配置。**

YAML 已提供可运行的默认值，不修改也可以部署：

| 配置 | 默认值 |
| --- | --- |
| 网页端口 | `17900` |
| MySQL 应用用户名 | `springagent` |
| MySQL 应用密码 | `springagent-mysql` |
| MySQL root 密码 | `springagent-mysql` |
| RabbitMQ 用户名 | `springagent` |
| RabbitMQ 密码 | `springagent-rabbitmq` |
| 应用镜像 | `ghcr.io/radiant303/springagent:latest` |

密码、JWT 密钥、内部令牌和网页端口均在 `x-settings` 中修改一次，通过 YAML 锚点自动复用。JWT 密钥和内部令牌需各自至少 32 字节。

> 默认密码和密钥公开。公网部署建议在首次启动前更换为独立随机值；

**3. 拉取镜像并启动。**

```bash
docker compose pull &&
docker compose up -d --wait --wait-timeout 600
```

**4. 查看状态并访问网页。**

```bash
docker compose ps -a
docker compose logs --tail=100 springagent
```

浏览器访问 `http://服务器IP:17900`；本机部署访问 `http://localhost:17900`。远程访问时需允许网页端口通过服务器防火墙或云安全组。

首次注册的账号成为站长。登录后台后，配置模型服务商、API Key、模型及默认模型，再按需配置积分资费、邮箱和搜索服务。首次注册前建议限制访问来源，避免别人抢先注册为站长；公网正式使用建议配置 HTTPS。

### 升级部署

以下步骤适用于已经使用当前 `springagent` 单镜像方案部署的用户。保持原部署目录、项目名、账号、密码、密钥和持久卷不变。

**1. 进入原部署目录，下载升级脚本。**

```bash
cd ~/springagent &&
mkdir -p deploy &&
curl -fL \
  https://raw.githubusercontent.com/Radiant303/spring-harness/master/deploy/upgrade.sh \
  -o deploy/upgrade.sh
```

**2. 执行升级。**

```bash
cp -p docker-compose.yml docker-compose.yml.bak &&
bash deploy/upgrade.sh
```

升级脚本按以下顺序执行：

1. 拉取镜像，检查项目名、挂载方式及 RabbitMQ 节点兼容性；拉取或前置检查失败时，不停止旧应用。
2. 停止 `springagent` 和 Nginx，等待基础服务就绪。
3. 备份 MySQL、完整工作文件卷和缓存密钥。
4. 重建 `springagent`，由容器启动命令执行一次数据库迁移。
5. 应用健康后启动 Nginx。

备份保存在部署目录下的 `backups/时间戳目录/`：

- `mysql.sql`：数据库备份。
- `app-data.tar.gz`：完整共享文件目录，包含工作文件和密钥。
- `secrets.tar.gz`：缓存密钥的单独备份。

可指定备份目录：

```bash
BACKUP_ROOT=/srv/springagent-backups bash deploy/upgrade.sh
```

迁移或启动失败时，脚本保留备份并停止应用。
确认迁移成功时请自行选择保留或删除备份。

**3. 核对结果。**

```bash
docker compose ps -a
docker compose logs --tail=100 springagent
```
