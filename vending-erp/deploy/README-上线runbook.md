# 售卖机 ERP · 上线 runbook（A 机 · 合 main 即上线）

> **2026-09-10 重写。** 旧版是 2026-08-07 写给阿里云 ECS（121.40.120.226 · /data/apps/vend/ · vend.aoleplat.com）的「rsync + 手工 compose」手册。
> 那台 ECS 2026-08-18 已停用，全部系统搬到负责人家里的 Mac（**A 机** · Docker + Cloudflare Tunnel），部署机制换成 **push 即部署**。
> 照旧手册跑会打到已停用的 ECS。本目录的 `dns-records.md` / `caddy-site.conf` / `flyway-prod-checklist.md` 同为 ECS 时代文件，只作考古。
> 机制真相源：dev-standards `规则分册/§3.7-部署架构迁移-20260818-家用Mac自动拉取.md`；清单真相源：ops-monitoring `deploy/systems.tsv` 的 `vend` 行。

## 一句话

**改代码 → 开分支 → PR 合进 `main` → 等它被拾取（最多 5 分钟）+ 构建几分钟 → 上线。** 不 rsync、不 ssh 服务器、不自己跑 docker 部署命令。

A 机 launchd 任务 `com.yh1.auto-deploy` 每 300 秒跑一次 `deploy.sh deploy --auto --all`，15 个系统串行，对 vend 固定四步：
`git fetch` + `merge --ff-only` 拉 `origin/main` → `docker compose build` → `up -d` → HTTP 探活；成败推 Bark 到负责人手机。
远程没有新提交就一行「代码无更新，跳过重建（自动模式）」，什么都不动。

## 关键参数（A 机）

| 项 | 值 |
|---|---|
| 部署机 | A 机（家里 Mac Studio · M2 Max）。`ops where` 一眼确认 |
| 清单名 | `vend` —— `ops status vend` / `ops logs vend` / `ops deploy vend` 都用它 |
| 远程仓 · 跟踪分支 | `github.com/dgsf2027/vending-erp` · `main`（2026-08-31 随生态平台 16 仓从 dgsd2025 迁到 dgsf2027） |
| A 机检出 | `~/系统开发/智慧园区`（仓根；`vending-erp/` 是子目录） |
| compose 执行目录 | `~/deploy-links/vending-erp/deploy`。`~/deploy-links/vending-erp` 是指向 `~/系统开发/智慧园区/vending-erp` 的纯 ASCII 软链：中文路径会让 Docker bake 报 `x-docker-expose-session-sharedkey contains value with non-printable ASCII characters` |
| compose 文件 | `docker-compose.prod.yml` + `docker-compose.local.yml` 叠加（local 只多一条 `8089:80`） |
| compose 项目名 | `vending`。来自 A 机 `deploy/.env` 里额外的一条 `COMPOSE_PROJECT_NAME=vending`（模板 `.env.prod.example` 里没有这条）。售卖机 / 租凭 / 洪源润三家的 compose 目录都叫 `deploy`，不设项目名会互相当孤儿删掉，2026-08-17 的 `deploy_vend_mysql_data` 旧卷就是这坑的疤 |
| 容器 | `vend-mysql`（mysql:8.0 · 库 `vend_prod` · 卷 `vending_vend_mysql_data`）· `vend-server`（8081，仅容器网内）· `vend-web`（nginx，宿主 8089）。三个都是 `restart: unless-stopped` |
| 本机入口 | `http://127.0.0.1:8089/`；后端探活 `GET /api/v1/health` → `{"code":200,...}` |
| 公网入口 | `https://vend.vvaix.com`（Cloudflare Tunnel，`~/.cloudflared/config.yml` ingress → `localhost:8089`；Cloudflare Access 已撤，直达系统自身登录页） |
| 门户 SSO | 生态管理平台 `eco.vvaix.com` 卡片免登；租户白名单 `SSO_ALLOWED_TENANT_IDS`（生态平台 = `T000004`） |
| `.env` | `deploy/.env`（600 权限 · 不入库 · 永不进对话）。变量名 = 模板 `.env.prod.example` 全部 + A 机自加的 `COMPOSE_PROJECT_NAME` |

## 日常上线（默认路径）

1. 开分支改代码；前端 `pnpm typecheck`（vue-tsc），后端 `mvn -s settings.xml test`。
2. PR 合进 `main`（一功能一分支一 PR）。
3. 等：最多 5 分钟被拾取，再加 maven + vite 构建几分钟。看进度和构建报错只有一个地方：A 机 `~/系统开发/运维监控/deploy/logs/deploy-<日期>.log` 里找 `部署 vend`（失败也会推 Bark，`deploy/deploy-state.json` 的 `lastError` 存最后一次失败原因）。`ops logs vend` 看的是**容器运行日志**，构建报错不在里面。
4. 验：
   - `ops status vend` 三个容器 Up；`curl -s http://127.0.0.1:8089/api/v1/health` 返回 code 200。deploy.sh 自己只探 nginx 首页 `http://127.0.0.1:8089/`，后端起不来（典型：合了一条坏 migration，Flyway 启动即炸）它照样报「部署完成」，所以 health 要自己 curl；
   - 浏览器开 `https://vend.vvaix.com`，**侧栏底部「版本 xxxxxxx」= 刚合并的提交号**（ops-monitoring #66 起，deploy.sh 构建时自动注入 `GIT_SHA/GIT_DATE/GIT_BRANCH`；显示 `unknown` = 这份镜像不是经 deploy.sh 构建出来的）；
   - 改了前端却看不到新界面：刷新一次再查版本号。nginx 对 `index.html` 是 `no-store`，正常刷新就会拿到新 hash 的 js，不需要清缓存。

## 什么情况不会自动上线（先查这三条）

| 日志里的话 | 原因 | 处置 |
|---|---|---|
| `代码无更新，跳过重建（自动模式）` | 改动没进 `main`（还在分支 / PR 没合） | 合 PR |
| `远程有 N 个新提交，但工作树有未提交改动 → 自动模式跳过（不动容器）`，本轮汇总行 `跳过（本机改动中）：vend` | A 机检出 `~/系统开发/智慧园区` 被人直接改了。脏树会让 vend 的自动部署从此每轮跳过，**连跳 6 轮（约 30 分钟）才推 Bark**，之前是静默的 | 到 A 机 `git status` 收编或丢弃；开发请在别的检出 / worktree 做，A 机那份只当部署树。手动 `ops deploy vend` 撞上脏树打的是 `已中止 vend 的部署`，同一件事 |
| 带「构建」字样的 ❌ 行：`构建失败，容器保持原样没动` / `构建未开始，容器保持原样没动`（磁盘水位闸）/ `构建超时 … 已强制中断` / `Docker daemon 无响应` | Dockerfile / 依赖 / 盘满 / Docker 引擎卡死 | 线上仍是旧版本，服务没断。按日志修好再 push；自动模式 30 分钟后重试，3 次仍失败就等新提交 |

## 数据库与 schema

- Flyway 跟着 `vend-server` 启动自动跑（`application.yml` 里 `spring.flyway.enabled: true`）：`backend/src/main/resources/db/migration/` 下新的 `V*.sql` 合进 main 后随下一次部署生效，**不需要人手 apply**（与图南「自动部署不跑 migration」不同，那是 drizzle 的口径）。合了 migration 的那次部署，务必自己 `curl /api/v1/health`，见上一节。
- 破坏性变更（删列 / 改类型 / 大表回填）合并前先备份：`ops dump vend`。改数据走 `ops db vend "SQL"` / `ops sql vend <文件>`（自动备份 + 审计留痕 + Bark）。
- 🔴 **没有一键回滚**：出事只能 `git revert` 再 push，再等一次拾取 + 构建；`ops rollback vend` 只能退镜像到上一版 `:prev-vending`，**schema 改动退不回来**，谨慎度按此定。

## 手工重建（只在需要时）

```bash
ops deploy vend              # 走同一套 deploy.sh：拉 main + build + up + 探活，版本号自动注入
ops deploy vend --no-cache   # 依赖 / 基础镜像疑似脏了才用，慢
ops deploy vend --recreate   # 只改了 .env、镜像没变时用：强制重建容器
ops rollback vend            # 退到构建前打的 :prev-vending 镜像，约 1 分钟，不回 schema
```

绕过 deploy.sh 直接 `docker compose build`：必须带 `-p vending`（否则项目名变成目录名 `deploy`，连到空卷），且侧栏版本号会是 `unknown`，要显示得先 export：

```bash
cd ~/deploy-links/vending-erp/deploy
export GIT_SHA=$(git rev-parse --short HEAD) GIT_DATE=$(git log -1 --format=%cI) GIT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
docker compose -p vending -f docker-compose.prod.yml -f docker-compose.local.yml build
docker compose -p vending -f docker-compose.prod.yml -f docker-compose.local.yml up -d
```

## 首次部署 / 换机器（一次性，A 机已做完）

1. clone 到纯 ASCII 路径，或建 `~/deploy-links/<名>` 软链；`docker network create vend-edge`（compose 声明为 external）。
2. `cp .env.prod.example .env && chmod 600 .env`，再加一行 `COMPOSE_PROJECT_NAME=vending`。`DB_PASSWORD`、`VEND_AUTH_SECRET`、SSO 的 `SSO_ENABLED` / `SSO_APP_ID` / `SSO_CLIENT_SECRET` / `SSO_ALLOWED_TENANT_IDS` 由负责人自己填，AI 不经手明文。
3. ops-monitoring `deploy/systems.tsv` 加一行（name / label / project / workdir / files / repos / port / domain），`ops deploy vend` 跑首轮；Flyway 首启自动建表。
4. Cloudflare Tunnel `~/.cloudflared/config.yml` 加 `vend.vvaix.com → http://localhost:8089` ingress，重启 cloudflared。
5. 门户管理后台注册子系统（回调 `https://vend.vvaix.com/api/v1/sso/callback`），拿到 app_id / client_secret 填 `.env`，`SSO_ENABLED=true`，`ops deploy vend --recreate` 重建 vend-server。验收：门户卡片点进来自动落 `/dashboard`，`/api/auth/me` 200 无 401。
6. 首登口径：按 `portal_uid` 找账号，找不到自动建号（最低角色 `REGISTER_DEFAULT_ROLE`；账号表为空才给「老板」）。提权改 `yc_vend_auth_user.role` 后重新登录生效。

## 本地开发（开发机，不是服务器）

后端默认连 `127.0.0.1:3308/vend_dev`，那是 `vending-erp/docker-compose.yml` 起的开发库容器 `vend-mysql`（只绑回环口，口令见该文件）：

```bash
cd vending-erp && docker compose up -d               # 第一次；之后机器重启若容器没自己起来：docker start vend-mysql
cd backend && mvn -s settings.xml spring-boot:run    # 8081
cd frontend && pnpm dev                              # Vite，/api 代理到 8081（VITE_PROXY_TARGET 可改）
```

**顺序固定：先库、再后端、再前端。** 库没起来后端起不来（连不上 3308），前端起了页面能开但接口全报错。
这只是开发机的事，与线上无关：A 机三个容器 `restart: unless-stopped` + Docker Desktop 开机自启，服务器重启后自己回来。
