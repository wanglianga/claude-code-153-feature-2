# 小区装修垃圾预约清运称重计费投诉追责服务

基于 **Spring Boot 3 + MySQL 8 + RabbitMQ** 的后端服务，覆盖装修垃圾全生命周期：

```
业主装修登记 → 施工队每日上报 → 物业审核预约（容量规则）→ 清运公司派单（限行/核载/时段冲突）
→ 司机到场（GPS 停留点回传）→ 进出场称重（异常检测）→ 处置场回执 → 自动计费结算
→ 称重差异复核（合并清运拆分/设备异常/司机误填）→ 投诉处理与追责还原
```

## 功能总览

| 角色 | 能力 |
|---|---|
| 业主 OWNER | 装修登记（房号/施工队/工期/垃圾类型/清运频次/押金）、临时加袋、投诉、费用争议、查看押金流水与追溯 |
| 施工队 WORKER | 每日上报袋装数量/堆放位置/分类（木板、玻璃、瓷砖、混入生活垃圾）、临时加袋 |
| 物业 PROPERTY | 审核生成清运预约、垃圾点容量管理、临时围挡扩容、违规豁免、投诉处理、**称重差异复核（证据比对、多户拆账确认、车辆解冻）**、物业端看板 |
| 清运公司 COLLECTOR | 派单（门禁放行单号、尾号限行、核载校验、时段冲突校验、冻结车辆拦截）、司机到场、**GPS 轨迹/停留点回传**、称重回传、处置回执、清运端看板 |
| 财务 FINANCE | 业主/施工队/物业垫付/清运公司四类账单、收款确认、费用争议调整、**复核订正结算复核**、财务端看板 |

**核心业务规则**

- **容量规则**：审核预约时校验 `垃圾点已堆放 + 本批袋数 > 有效容量 × 90%` 则不能简单通过，返回"错峰堆放 / 物业安排临时围挡"提示；围挡扩容（`POST /api/waste-points/{id}/fence`）后可重新审核通过。
- **违规自动处理**：上报位置含"消防通道"→ 消防通道堆放违规（罚 200）；勾选混入生活垃圾 → 违规（罚 100）。违规经 MQ 触发押金扣款并生成施工队罚款账单；施工队缴清罚款后自动返还业主押金。
- **称重异常**：净重与按袋预估（25kg/袋）偏差超 20% → 标记异常、自动生成称重异常投诉并**自动立称重复核案**。
- **称重差异复核**：系统自动比对五类证据——①装车照片清点（可见袋数、照片中其他楼栋/房号标签，`POST /api/weigh-reviews/{id}/photo-evidence`）；②车载 GPS 停留点（其他垃圾点装车停留 ≥5 分钟，`POST /api/vehicle-stops`）；③车辆路线与时间窗（默认 ±6h）内同车其他派单/预约（已单独过磅的派单自动排除）；④处置场回执重量（地磅 vs 车载秤偏差 >10%）；⑤各户验收袋数×材质单袋估算（木板 18 / 玻璃 32 / 瓷砖 30 / 混凝土 35 / 混合 25 kg）。综合给出建议：**合并清运 / 称重设备异常 / 司机误填 / 无异常 / 证据不足**。证据（GPS、回执、照片）补传后自动重新比对。
- **复核结论落地**：
  - **合并清运**：按各户材质估算重量占比 + 现场装车顺序拆分车载净重（尾差归最后装车户），清运结算总额不变、按户重量占比分摊；被合并户补建业主账单（正常袋×8 元 + 加袋×8×1.5，押金优先抵扣、不足物业垫付）、释放垃圾点库存、补齐追溯状态；物业可逐户人工调整拆账重量（**合计必须等于车载净重且必填调整理由**）。
  - **称重设备异常**：以处置场回执重量重算清运公司结算单（原单标注订正，已付款转待补差/追回）。
  - **司机误填**：以现场验收袋数×25kg 重算清运结算。
  - 合并清运、司机误填默认**冻结车辆后续派单权限**（`POST /api/dispatches` 直接拦截），物业核查后凭说明解冻（`POST /api/vehicles/{id}/unsuspend`）；复核确认自动办结称重异常投诉。物业推翻系统建议、人工认定重量、认定证据不足均强制填写理由并全程留 `trace_event`。
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

脚本用 curl 驱动 124 项断言，覆盖：认证鉴权、主流程（上报→审核→派单→称重→处置→计费→追溯）、容量规则（拒绝→围挡→通过）、尾号限行、称重异常自动投诉、消防通道/混入生活垃圾违规扣款、施工队罚款返还押金、费用争议调整回冲、超时未清与三端看板，以及**称重差异复核全场景**（GPS 停留点+同车预约+照片标签五证比对判定合并清运、按材质估算与装车顺序拆分 336kg/40.32 元结算、被合并户补建账单与押金抵扣、垃圾点库存释放、拆账人工调整校验、冻结车辆派单拦截与物业解冻、处置回执补传自动改判设备异常并订正结算、照片清点判定司机误填并冻结、证据不足强制说明、异常投诉自动办结、复核看板统计）。

**本题实际执行的验证**：本仓库交付前已在本地（MariaDB 10.11 兼容 MySQL 协议，独立实例 3307 端口 + `APP_MQ_ENABLED=false` 降级模式）完整执行 `verify.sh`，**124/124 项全部通过**；`mvn package` 编译打包通过；OpenAPI JSON 已收录复核/轨迹/解冻端点，Swagger UI 可访问。Docker 镜像构建与 compose 编排未在本环境执行（无 Docker 守护进程），配置按标准多阶段构建与健康检查编写。

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
| 派单 | `POST /api/dispatches`、`POST /{id}/arrive`、`GET /api/dispatches`（冻结车辆派单被拦截）；车辆 `GET/POST /api/vehicles`、`POST /{id}/unsuspend`（物业解冻） |
| 车辆轨迹 | `POST/GET /api/vehicle-stops`（GPS 打点/装车停留点，复核证据） |
| 称重 | `POST /api/weigh-records`（触发自动计费，异常自动立复核案）、`GET /api/weigh-records` |
| 称重复核 | `POST /api/weigh-reviews/weigh-records/{id}/open`（人工立案）、`GET /api/weigh-reviews`、`GET /{id}`、`POST /{id}/photo-evidence`、`POST /{id}/analyze`、`POST /{id}/confirm`（确认结论/多户拆账/冻结车辆） |
| 处置回执 | `POST /api/disposal-receipts`（回执补传自动刷新复核分析） |
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
│   ├── controller/           # 15 个 REST 控制器（另有认证控制器在 auth/）
│   ├── entity|enums|repository/
│   ├── mq/                   # EventBus + 队列监听器
│   └── service/              # 14 个业务服务（含称重差异复核）
└── src/main/resources/application.yml
```
