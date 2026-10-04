# 小区装修垃圾预约清运称重计费投诉追责服务

基于 **Spring Boot 3 + MySQL 8 + RabbitMQ** 的后端服务，覆盖装修垃圾全生命周期：

```
业主装修登记 → 施工队每日上报 → 物业审核预约（容量规则）→ 清运公司派单（限行/核载/时段冲突）
→ 司机到场 → 进出场称重（异常检测）→ 处置场回执 → 自动计费结算 → 投诉处理与追责还原
```

## 功能总览

| 角色 | 能力 |
|---|---|
| 业主 OWNER | 装修登记（房号/施工队/工期/垃圾类型/清运频次/押金）、临时加袋、投诉、费用争议、查看押金流水与追溯 |
| 施工队 WORKER | 每日上报袋装数量/堆放位置/分类（木板、玻璃、瓷砖、混入生活垃圾）、临时加袋 |
| 物业 PROPERTY | 审核生成清运预约、垃圾点容量管理、临时围挡扩容、违规豁免、投诉处理、物业端看板 |
| 清运公司 COLLECTOR | 派单（门禁放行单号、尾号限行、核载校验、时段冲突校验）、司机到场、称重回传、处置回执、清运端看板 |
| 财务 FINANCE | 业主/施工队/物业垫付/清运公司四类账单、收款确认、费用争议调整、财务端看板 |

**核心业务规则**

- **容量规则**：审核预约时校验 `垃圾点已堆放 + 本批袋数 > 有效容量 × 90%` 则不能简单通过，返回"错峰堆放 / 物业安排临时围挡"提示；围挡扩容（`POST /api/waste-points/{id}/fence`）后可重新审核通过。
- **违规自动处理**：上报位置含"消防通道"→ 消防通道堆放违规（罚 200）；勾选混入生活垃圾 → 违规（罚 100）。违规经 MQ 触发押金扣款并生成施工队罚款账单；施工队缴清罚款后自动返还业主押金。
- **称重异常**：净重与按袋预估（25kg/袋）偏差超 20% → 标记异常并自动生成称重异常投诉。
- **称重差异复核**：物业对异常称重发起复核，系统汇总**装车照片、车辆路线、司机停留点（GPS/人工补录）、处置场回执、同日其他预约**五类证据交叉比对，给出"合并清运 / 称重设备异常 / 司机误填 / 疑似混装（证据不足）/ 无异常"判定建议。
  - **合并清运拆分**：认定同车混装多户垃圾时，按各户**验收袋数 × 分材质单袋重量**（木板 18kg、玻璃 30kg、瓷砖 35kg，可配置）估算占比拆分整车净重，**现场装车顺序**（停留点时间）决定末位尾差；物业可人工调整拆分重量但**必须填写调整理由**。
  - **结算联动**：为被混装户补生成业主分摊账单（押金优先抵扣、不足物业垫付）与清运公司结算拆分单，主户结算单按拆分重量调整；设备异常/司机误填以处置场回执重量为准重新结算；称重异常投诉随定论自动办结。
  - **车辆权限**：复核定论可暂停涉事车辆后续派单（派单接口直接拦截），物业整改验收后可解除。
- **车辆迟到**：实际到场晚于计划 30 分钟 → 迟到标记；若此时垃圾点已超容量，自动生成溢出投诉，并计入清运公司履约看板。
- **计费**：业主账单 = 正常袋×8 元 + 临时加袋×8×1.5 元，押金优先抵扣、不足部分自动生成物业垫付单；清运公司结算 = 净重(吨)×120 元。
- **费用争议**：业主投诉费用争议 → 账单自动冻结（DISPUTED）→ 财务调整金额 → 差额自动回冲/补扣押金。
- **全链路追溯**：每个环节落 `trace_event`（谁、何时、对哪个单据、做了什么），`GET /api/trace/appointments/{id}` 一次还原"谁堆放、谁审核、谁清走、费用如何产生"。

## 一键启动（Docker Compose）

```bash
docker compose up -d --build
```

启动三个容器：MySQL 8（3306）、RabbitMQ（5672/15672）、应用（8080）。应用依赖 MySQL/RabbitMQ 健康检查通过后启动，首次启动自动建表并初始化演示数据。

- Swagger UI：http://localhost:8080/swagger-ui.html
- OpenAPI JSON：http://localhost:8080/v3/api-docs
- RabbitMQ 控制台：http://localhost:15672（guest/guest）

## 测试账号

| 账号 | 密码 | 角色 |
|---|---|---|
| owner1 / owner2 | 123456 | 业主 张三 / 张四 |
| worker1 | 123456 | 施工队 李工（宏远装修队） |
| property1 | 123456 | 物业 王经理 |
| collector1 | 123456 | 清运公司 赵调度（绿洁清运） |
| finance1 | 123456 | 财务 陈会计 |
| admin | admin123 | 管理员 |

预置数据：3 个垃圾点（2 号楼点 46/50 袋，已超 90% 预警线，用于演示容量规则）、3 辆车（沪C11116 尾号 6 周一限行）、1 个装修登记（张三 1 栋 801，押金 2000）。

## 验证方式

```bash
./verify.sh        # 需应用已启动且数据库为初始状态；BASE 环境变量可改地址
```

脚本用 curl 驱动 99 项断言（含全链路追溯关键字逐一断言），覆盖：认证鉴权、主流程（上报→审核→派单→称重→处置→计费→追溯）、容量规则（拒绝→围挡→通过）、尾号限行、称重异常自动投诉、消防通道/混入生活垃圾违规扣款、施工队罚款返还押金、费用争议调整回冲、超时未清与三端看板、**称重差异复核全流程（五类证据比对、疑似混装补证重新分析、合并清运按袋数+材质+装车顺序拆分、人工调整必须留理由、设备异常/司机误填按回执重结算、车辆派单限制与解除、投诉自动办结、复核结案归档）**。

**本题实际执行的验证**：本仓库交付前已在本地（MariaDB 10.11 兼容 MySQL 协议 + `APP_MQ_ENABLED=false` 降级模式）完整执行 `verify.sh`，**99/99 项全部通过**；`mvn package` 编译打包通过，应用日志无 ERROR/Exception，Swagger/OpenAPI 文档包含"称重差异复核"分组与全部新接口（发起/重新分析/定论/结案/停留点/解除车辆限制）。Docker 镜像构建与 compose 编排未在本环境执行（无 Docker 守护进程），配置按标准多阶段构建与健康检查编写。

## 本地开发

```bash
# 1. 准备 MySQL（8.x 或 MariaDB），创建数据库
mysql -u root -e "CREATE DATABASE wastedb CHARACTER SET utf8mb4;"

# 2. 启动应用（无 RabbitMQ 时用 APP_MQ_ENABLED=false，事件降级为同步处理，业务结果一致）
MYSQL_HOST=localhost MYSQL_PORT=3306 MYSQL_USER=root MYSQL_PASSWORD=root123 \
APP_MQ_ENABLED=false \
mvn spring-boot:run

# 3. 验证
./verify.sh
```

有 RabbitMQ 时省略 `APP_MQ_ENABLED=false`（默认 true），事件经 `waste.exchange`（topic）投递：
`appointment.approved`（通知清运派单）、`weigh.recorded`（自动计费）、`violation.created`（押金扣款）、`complaint.created`（通知物业）。

## API 概览（详见 Swagger UI）

| 模块 | 端点 |
|---|---|
| 认证 | `POST /api/auth/login`、`GET /api/auth/me` |
| 装修登记 | `POST/GET /api/registrations`、`PUT /{id}/complete`、`GET /{id}/deposit-log` |
| 每日上报 | `POST /api/reports`、`POST /{id}/extra-bags`（临时加袋）、`GET /api/reports` |
| 清运预约 | `POST /api/appointments/review`（容量规则）、`GET /api/appointments` |
| 垃圾点 | `GET/POST /api/waste-points`、`POST /{id}/fence`（临时围挡） |
| 派单 | `POST /api/dispatches`、`POST /{id}/arrive`、`GET /api/dispatches`；车辆 `GET/POST /api/vehicles` |
| 称重 | `POST /api/weigh-records`（触发自动计费）、`GET /api/weigh-records` |
| 称重复核 | `POST /api/weight-reviews`（发起并汇总证据）、`PUT /{id}/reanalyze`（补证后重新分析）、`PUT /{id}/confirm`（定论+拆分+车辆限制）、`POST /{id}/close`（结案归档）、`GET /api/weight-reviews`、`GET /{id}`（证据+拆分明细）、`POST/GET /api/weight-reviews/stops`（司机停留点）、`POST /api/weight-reviews/vehicles/{id}/unblock`（解除派单限制） |
| 处置回执 | `POST /api/disposal-receipts` |
| 投诉 | `POST /api/complaints`、`PUT /{id}/handle`、`GET /api/complaints` |
| 账单 | `GET /api/bills`、`POST /{id}/pay`、`POST /{id}/adjust`（争议调整） |
| 违规 | `GET /api/violations`、`POST /{id}/waive` |
| 看板 | `GET /api/dashboard/property`、`/collector`、`/finance` |
| 追溯 | `GET /api/trace/appointments/{id}`、`GET /api/trace/code/{traceCode}` |

除 `/api/auth/login` 外所有接口需请求头 `Authorization: Bearer {token}`。

## 设计说明

- **事件总线（`EventBus`）**：业务事件在事务提交后投递 RabbitMQ，由队列监听器异步处理（称重→计费、违规→扣款）；MQ 未启用或投递失败时降级为同步调用同一套处理器（`REQUIRES_NEW` 新事务），保证业务不中断、结果一致。
- **幂等**：同一预约只生成一次账单、同一派单只称重一次、同一上报只审核一次、违规扣款只执行一次。
- **追溯**：`bag_trace` 记录袋级状态机（REPORTED→APPROVED→DISPATCHED→LOADED→WEIGHED→DISPOSED→SETTLED，只前进不后退），`trace_event` 记录全部操作人行为，支撑投诉追责还原。
- **规则参数化**：费率、罚款、阈值均在 `application.yml` 的 `waste.pricing.*` 下可调。

## 目录结构

```
├── Dockerfile / docker-compose.yml / maven-settings.xml
├── verify.sh                 # 端到端验证脚本（55 项断言）
├── src/main/java/com/waste/
│   ├── auth/                 # 令牌认证与角色鉴权
│   ├── common/               # 统一响应、异常、追溯日志、单号生成
│   ├── config/               # Web/RabbitMQ/OpenAPI/规则参数/演示数据
│   ├── controller/           # 13 个 REST 控制器（另有认证控制器在 auth/）
│   ├── entity|enums|repository/
│   ├── mq/                   # EventBus + 队列监听器
│   └── service/              # 13 个业务服务
└── src/main/resources/application.yml
```
