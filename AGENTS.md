# 注释规范

总则：**注释只描述当前类/方法/字段是什么，代码本身能表达的一律不写。**
适用于 Java / Python / 前端（JS、HTML、CSS）全部源码。

## 分层标准

**类 / 模块注释：一句话"这是什么"**

```java
/**
 * WS 容器缓冲配置。
 *
 * @author hanbing
 * @since 2026-10-02
 */
```

不写机制、时序、方向、不变量、兜底策略——那些属于代码和提交信息。Python 模块 docstring 同理，压成一句。

**方法注释：一句功能描述 + javadoc 标签**

```java
/**
 * 新建 work；数量达上限或存储配额不足时抛 409
 *
 * @param userId 用户 ID
 * @param name   项目名
 * @return 新建的 work
 * @throws BizException 超出数量上限（409）
 */
```

不写 `<p>` 机制段落。

**字段 / 常量注释：一行，说值的含义**

名字和类型已表达的不写（`/** Stream 键前缀 */` 加在 `KEY_PREFIX` 上是复述，删）。魔数、警戒值必须写（`/** 单轮输入长度上限（字符） */`）。

**行内注释：一行，贴着代码说"这行在干什么"**

允许：`// 幂等：同一 turnId 重复派发不重复扣`、`// 正=少补，负=多退`。
不允许：翻译代码、多行叙事。

## 禁止清单（违反即删）

| 违规类型 | 反例 |
|---|---|
| 括号复述代码可见事实 | `（拦截器注入）`、`（仅站长）`、`（按 id 倒序）`、`（站长在后台配置、即改即生效）`、`（与 model_store.py 的 CACHE_KEY 一致）`、`@return 设置快照（registrationOpen、workMaxBytes…）`、`（拼 URL 的 scheme/host 用）`、`（判定是否经 Nginx 用）`、`（配置校验用）`、`（admin.js init 调用）`、`（文件上传功能需要）`、`（站长可选的文件存储后端）` |
| 跨服务/跨类/跨表/跨文件引用 | `网关侧生成`、`引擎直读 DB`、`与 Python 侧一致`、`system_settings.work_max_bytes`、`@link` 指向其他类、`（后端校验 http/https 前缀）`、`（业务层 FileStorageService.MAX_FILE_BYTES 再卡一道）`、`与网关 multipart 上限对齐`、`网关 app.data-root 只读挂载` |
| 配置来源与消费方叙事 | `站长在系统设置里配置链接，未配置则隐藏`——谁配置、前端怎么响应都不归这段代码管 |
| 机制/时序/方向叙事 | `两个方向要分别处理：客户端方向……服务端方向……`、`WsSci 注册顺序在 TomcatStarter 之后`、`延迟双删策略：第一遍……第二遍……`、`指路不碰内容：OSS → 302 签名 URL；本地 → X-Accel-Redirect 让 Nginx 发文件`、`先文件后库，回滚时补偿删除新建目录` |
| 历史与迁移叙事 | `与原静态配置一致；静态配置随之退役`、`不再读静态配置，而是……`、`3.5.9 起拆到独立构件` |
| 命名与线格式琐事 | `user_id 为蛇形命名`、`字段保持驼峰风格` |
| 黑话 | `数据面`、`阶段④`、`backlog` |
| 常量值复述进注释 | `桶空（本分钟已连续失败 10 次）`、`文件列表（1~50 个）`——数值会漂移，看常量本身 |

## 允许保留（代码里看不出来的信息）

- 取值域与警戒值：`null = 跟随全局设置`、`0 = 只探查`、`finished / cancelled / error`
- 外部约束：`SMTP 登录账号；QQ 要求 From 与登录账号一致`、`input_tokens 是 inclusive 桶`
- 值形状：`雪花 ID`
- 单行行内语义：贴着对应代码行解释这一行的业务含义
- `@author hanbing`、`@since YYYY-MM-DD`（全项目类注释必带）

## 语言与格式

- 中文；全角标点；完整短句
- 行内注释统一 `//`（Python 用 `#`），与上方代码同缩进
- 删掉注释后留下的句号、空行顺手清理，不留残骸

## 提交前自检

`git diff` 中不应出现注释以外的变化；行内删除复述括号后检查标点与空格无残留。

## 判断口诀

写之前问一句：**不看这段代码，这个注释提供的信息能独立成立吗？**
看代码三秒就能明白的，一个字都不写。

# 数据库变更联动

修改表、字段、索引、约束或初始化数据时，必须同次检查以下内容；不涉及的可不改，但不得漏查：

- 在 `alembic/versions/` 新增顺序迁移，处理旧数据和默认值；已发布的迁移不得改写。无法安全降级时，`downgrade()` 要明确抛错。
- 同步 `spring_agent/cloud/db.py` 的模型，以及网关对应的 `entity`、Mapper、SQL、DTO 和 Service。
- 全局搜索表名和字段名，检查原生 SQL、计费、缓存、内部接口、管理页面和前端展示。
- 迁移与两端模型的类型、长度、可空、默认值、唯一约束和索引保持一致。
- 验证全新安装执行 `alembic upgrade head`，并从一个旧版本携带旧数据升级，确认数据保留且应用能启动。
- Docker 镜像必须包含新迁移，不得把手工执行 SQL 当成发布步骤。
- 升级通过 `deploy/upgrade.sh` 先停应用，再备份数据库和共享目录，迁移成功后才启动；迁移失败不得自动重试 DDL、清库、删卷或自动降级。

# 版本发布

发布由 tag 触发，`.github/workflows/release.yml` 负责构建多架构镜像、推送 GHCR 和生成 GitHub Release。

## 发布前

1. 修改 `pyproject.toml` 的 `version`，预发布用 PEP 440 写法（如 `0.1.1-rc1`）。
2. 执行 `uv lock`，同步 `uv.lock`；Docker 构建使用 `uv sync --frozen`，锁文件过期会失败。
3. 在 `CHANGELOG.md` **最上方**追加条目，格式必须是：

   ```markdown
   ## vX.Y.Z (YYYY-MM-DD)：一句话标题

   ### 功能新增
   * ……

   ### 优化与修复
   * ……
   ```

4. 提交并推送，确认 `.github/workflows/release.yml` 就在这次提交里：

   ```bash
   git add pyproject.toml uv.lock CHANGELOG.md .github
   git commit -m "chore: 发布 vX.Y.Z"
   git push origin master
   ```

> tag 指向的 commit 必须包含 workflow 文件，否则推送 tag 不会触发发布。

## 打 tag

```bash
git tag -a vX.Y.Z -m "vX.Y.Z"
git push origin vX.Y.Z
```

tag 去掉 `v` 后必须与 `pyproject.toml` 的 `version` 完全一致，`CHANGELOG.md` 也必须有对应标题，否则 `prepare` 阶段直接失败，不会推送任何镜像。

## 流水线行为

- `prepare`：校验版本号与 CHANGELOG。
- `build`：`linux/amd64` 与 `linux/arm64` 由原生 runner 并行构建，按 digest 推送。
- `merge`：合成多架构 manifest，打版本 tag；正式版额外更新 `latest`，预发布不动 `latest`。
- `release`：截取 CHANGELOG 中该版本段落生成 GitHub Release；已存在则更新说明。

版本与镜像的对应关系：

| tag | 镜像标签 | Release | `latest` |
|---|---|---|---|
| `v0.1.1-rc1` | `0.1.1-rc1` | Pre-release | 不变 |
| `v0.1.1` | `0.1.1`、`latest` | 正式版 | 更新 |

站长端左下角的“版本更新”卡片读取 GitHub `releases/latest`，只识别正式版。仓库只有预发布时该接口返回 404，卡片不显示。

## 发布后核对

```bash
gh release view vX.Y.Z
docker buildx imagetools inspect ghcr.io/radiant303/springagent:X.Y.Z
```

服务器升级使用 `deploy/upgrade.sh`，它会先停应用并备份数据库和 `/data/cloud`，再启动新镜像执行迁移。

## 重发同一个 tag

```bash
git tag -d vX.Y.Z
git push origin :refs/tags/vX.Y.Z
# 修复并提交后重新打 tag
git tag -a vX.Y.Z -m "vX.Y.Z"
git push origin vX.Y.Z
```

## 手动兜底

CI 不可用时手动构建推送，仍需先改版本、写 CHANGELOG、提交。手动构建只包含本机架构：

```bash
VERSION=X.Y.Z
IMAGE=ghcr.io/radiant303/springagent
docker login ghcr.io -u Radiant303
docker build --build-arg APP_VERSION="$VERSION" -t "$IMAGE:$VERSION" .
docker push "$IMAGE:$VERSION"

# 仅正式版：
docker tag "$IMAGE:$VERSION" "$IMAGE:latest"
docker push "$IMAGE:latest"
```

Release 说明从 `CHANGELOG.md` 对应段落复制。预发布不要推 `latest`。

## 常见失败

- `build` 报 `denied: permission_denied: write_package`：`GITHUB_TOKEN` 没有包写权限。先确认仓库 Settings → Actions → General → Workflow permissions 为 “Read and write permissions”，再到 package 的 Package settings → Manage Actions access 把仓库加为 **Write**。两处改完后重跑失败的 workflow，不需要重新打 tag。
