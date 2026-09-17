# 智慧园区售卖机 ERP

前后端骨架(M1-0)。技术栈:Spring Boot 2.7 + MyBatis-Plus + Flyway + MySQL 8 / Vue 3 + Vite 5 + Element Plus。

## 本地起步(三条命令)

```bash
# 1. 起数据库(MySQL 8 · 127.0.0.1:3308 · root/vend123 · 库 vend_dev)
docker compose up -d

# 2. 起后端(端口 8081 · context-path /api;国内网络用阿里云镜像加 -s settings.xml)
#    注意:本机 brew 默认 openjdk 是 26,须指到 JDK 17(JDK 23+ 默认关注解处理,且 SB2.7 不保证兼容)
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -s settings.xml spring-boot:run

# 3. 起前端(Vite dev server · /api 代理到 8081)
cd frontend && pnpm install && pnpm dev
```

验证:`curl http://127.0.0.1:8081/api/v1/health` 应返回 `{"code":200,...}`。

## 目录

- `backend/` — Spring Boot 2.7.18(JDK 17 可跑,字节码 release=8 保持 ole 部署兼容),包名 `top.aole.vend`,DDD 分层 `modules/<module>/{interfaces,application,domain,infrastructure}`
- `frontend/` — Vue 3.4 + Vite 5 + TS + Element Plus 2.6 + Pinia + UnoCSS
- `backend/src/main/resources/db/migration/` — Flyway 迁移脚本(V1.0.0__placeholder.sql 占位,后续替换真 DDL)

## 约定

- 统一返回体 `R{code,message,data}`,code=200 成功
- 接口文档 Knife4j:http://127.0.0.1:8081/api/doc.html
- SSO 留位(`sso.enabled=false`),AI 网关开发期全 mock(`MockLlmService`),不发真请求
- LLM key 等敏感配置走 ENV,见 `backend/.env.example` / `frontend/.env.example`

## 采购列表导入

采购页的「采购入库单」和「订货单」标题右侧均提供「列表导入」。下载对应模板，填写商品档案中的商品编码、数量和单价，再上传 `.xlsx` 校验。

- 一个文件对应一张单据的明细；供应商、日期和备注在校验后的录单窗口填写。
- 数量和单价按商品基本单位（瓶、袋等）填写，不按整箱；数量最多 3 位小数，单价最多 4 位小数。订货预计单价可空，入库进货单价必须大于 0。
- 每次最多 500 行、5MB，仅一个工作表；重复编码、未知商品、清仓商品、公式或无效数值会显示 Excel 行号，需全部修正后才能带入明细。
- 校验与预览不写入业务数据；导入后仍需保存。订货默认保存草稿，可勾选立即下单；入库可仅存草稿或确认入库，库存与在途沿用原流程。
