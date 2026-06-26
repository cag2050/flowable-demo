# Flowable 流程中台 ↔ 业务系统 A 的消息交互方案对比

## 一、先理清两个交互方向

| 方向 | 场景 | 典型例子 |
|------|------|----------|
| **A → 流程中台** | 业务驱动流程 | 发起流程、提交审批、撤回、查询任务 |
| **流程中台 → A** | 流程驱动业务 | 节点到达通知、审批结果回写、待办推送、流程结束回调 |

很多人只关注「A 调流程」，但真正容易出问题的是**流程中台反向通知 A**（异步、解耦、可靠性要求高），这一段最适合用消息机制。

---

## 二、具体消息交互方式对比

### 方案 1：同步 REST/RPC 调用（非消息，作对照）
- A 直接调 Flowable REST API（本仓库就是 `flowable-rest`），流程中台通过 `HttpServiceTask` 或监听器回调 A 的接口。
- **优点**：简单、实时、调试直观、强一致易实现。
- **缺点**：强耦合，A 宕机会阻塞流程；超时/重试要自己兜底；削峰能力差。
- **适用**：内网、低并发、强一致、链路短的场景。

### 方案 2：MQ 消息队列（RabbitMQ / RocketMQ / Kafka）— 推荐主力
- 流程中台在**执行监听器 / 任务监听器 / 全局事件监听器**里把事件投递到 MQ，A 订阅消费；A 的动作也可发消息进队列由流程中台消费。
- **优点**：彻底解耦、异步削峰、天然支持重试与堆积、可广播给多个业务方。
- **缺点**：引入中间件运维成本；最终一致；需处理幂等、顺序、消息丢失。
- **细分对比**：

| 中间件 | 特点 | 适合 |
|--------|------|------|
| RabbitMQ | 路由灵活、延迟队列方便、吞吐中等 | 审批通知、待办推送等业务消息 |
| RocketMQ | 事务消息、顺序消息、堆积能力强 | 要求「流程状态与业务库一致」的事务场景 |
| Kafka | 超高吞吐、日志型、顺序按分区 | 流程事件流、审计、大数据分析 |

### 方案 3：Flowable 内置 BPMN 消息/信号事件
- 用 BPMN 的 **Message Event / Signal Event**，A 通过 `runtimeService.messageEventReceived()` 或 REST 触发流程内消息节点。
- **优点**：消息语义画在流程图里，业务可见；与流程引擎原生集成。
- **缺点**：这是「流程内部的消息」，跨系统时仍需外层传输通道（REST/MQ）承载；signal 是广播、message 是点对点，别用错。
- **适用**：流程需要「等待业务系统某个事件才继续」的中间等待节点。

### 方案 4：事务性发件箱（Transactional Outbox）+ MQ
- 流程中台在本地事务里同时写业务状态和一张 `outbox` 表，再由独立投递器发到 MQ。
- **优点**：解决「流程状态已变更但消息没发出去 / 发了但事务回滚」的经典分布式难题，保证最终一致。
- **缺点**：实现复杂，需要轮询或 CDC（如 Debezium）。
- **适用**：对一致性要求高、不能丢消息的核心审批/资金类流程。

### 方案 5：Webhook / 回调
- 流程中台在节点完成时 HTTP 回调 A 注册的 URL（可配置）。
- **优点**：A 侧无需引入 MQ，接入轻；URL 可动态配置，多租户友好。
- **缺点**：本质是同步 HTTP，需自建重试队列 + 签名校验，否则可靠性差。
- **适用**：对外/对第三方业务系统开放、对方不愿接 MQ 时。

### 方案 6：Flowable Event Registry（事件注册中心）— 官方原生 MQ 集成
- Flowable 6.5.0+ 引入的独立事件引擎（本仓库用的 6.8.1 中已是与 BPMN/CMMN/DMN 并列的一级组件、运行时成熟可用于生产），把「外部消息系统」与「流程引擎」之间解耦。核心由四部分组成：
  - **Channel（通道）**：连接具体中间件的进/出站适配器，官方开箱支持 **Kafka / RabbitMQ / JMS**，也可自定义。分 inbound（收）和 outbound（发）。
  - **Event（事件定义）**：用 JSON 描述事件的字段、payload、关联键（correlation key），与具体队列无关。
  - **入站映射**：收到的消息按 correlation key 路由，可触发 **启动新流程** 或 **唤醒 Message Start / Receive 事件**（在 BPMN 里用 `EventRegistry` 类型的 Message Event）。
  - **出站映射**：流程里的 **Send Event Task** 把流程变量按事件定义序列化后投递到 Channel，发给 A。
- **优点**：
  - 官方原生、声明式配置（`.channel`/`.event` 文件），收发逻辑不写死在监听器代码里；
  - 中间件可插拔，换 Kafka↔RabbitMQ 只改 channel 定义，流程图不动；
  - 与 BPMN 消息事件天然打通，**流程图里可视化**收发节点；
  - 内置 correlation、payload 映射、多租户支持，省去手写路由/反序列化样板。
- **开源版 vs 商业版（6.8.1 实测边界，重要）**：
  - ✅ **运行时完全开源可用**：Event Registry 引擎本体、**Kafka / RabbitMQ / JMS** 三种官方适配器、HTTP 接收、以及 BPMN 的 Event Registry Start / Send Event Task / Receive Event Task / **Send-and-Receive Task**（用 post-commit 事务监听器+异步 job 解决「发出后等回执」竞态）都在开源版里；未内置的 MQ 可实现 `InboundEventChannelAdapter` 自定义接入。
  - ⚠️ **仅商业版**：Flowable Design 里 channel 模型编辑、event 数据映射的**图形化建模 GUI**；以及 **AWS SQS/SNS、Email、REST 通道**。开源版需手写并部署 `.channel`/`.event`（JSON）文件。
  - 🔁 引擎在开源版↔商业版**数据库兼容**，可先用开源后续平滑迁移商业版。
- **缺点**：
  - 开源版无可视化 channel/event 建模器与运维管理 UI，定义需手写文件维护；
  - 概念较多（channel/event/correlation）有学习曲线；
  - 可靠性仍取决于底层 MQ + 引擎事务，跨「业务库 + 流程库」的严格一致仍要配合 Outbox。
- **适用**：希望用 Flowable 官方方式标准化对接 MQ、让收发事件成为流程模型一部分、且想避免自己造监听器+序列化轮子的中台场景。

#### 方案 6 的四类 BPMN 收发事件节点（流程图里画的收发姿势）

| 节点 | 方向 | 作用 | 典型场景（中台↔A） |
|------|------|------|------|
| **Event Registry Start**（事件启动） | 收（inbound） | 收到某类事件时**新建一个流程实例** | A 发一条「提交申请」消息到 Kafka/MQ → 自动发起审批流程 |
| **Send Event Task**（发送事件任务） | 发（outbound） | 流程走到这里，把流程变量按 event 定义序列化后**投递到 outbound channel** | 节点到达/审批结果 → 发消息通知 A，发完即继续往下走 |
| **Receive Event Task**（接收事件任务） | 收（inbound） | 流程**停在此处等待**指定事件到来才继续（wait state） | 流程需等 A 完成某动作（如「外部系统校验通过」）才放行 |
| **Send-and-Receive Task**（收发一体任务） | 先发后收 | 一步内**发出事件并等待回执**，原子地处理「请求-应答」 | 中台发「请处理」给 A，并等 A 回「处理完成」再继续 |

几个关键区别：

- **Start vs Receive**：都是收消息，但 Start 是「没有实例 → 造一个新实例」；Receive 是「已有实例 → 唤醒它继续」。靠 **correlation key（关联键）** 把消息路由到正确的实例。
- **Send vs Send-and-Receive**：单纯 `Send` 发完不等待、立刻往下走；而 `Send-and-Receive` 解决了一个真实的**竞态问题**——若服务很快、或 Flowable 的库较慢，回执可能在流程实例还没进入「可接收」状态时就到了，导致丢失。它内部用 **post-commit 事务监听器 + 异步 job** 保证：先提交事务、确实进入等待态后，再异步接收回执继续。本质上是个**带回执等待的 wait state**。
- 这四个节点**开源版 6.8.1 全都有**；缺的只是 Flowable Design 的图形化建模 GUI，开源版用 `.channel`/`.event`/`bpmn20.xml` 文件手写即可。

#### 方案 6 如何使用（贴合本仓库：Spring Boot 2.7.18 + `flowable-spring-boot-starter` 6.8.1，嵌入式 + MySQL）

以 **RabbitMQ** 为例（「流程中台→A 业务通知」推荐它），换 Kafka 只改 channel 的 `type`。整体步骤：

```
加依赖 → 写 .event(事件定义) → 写 .channel(通道定义) → 在 BPMN 里挂节点 → 放到 resources 自动部署 → 触发验证
```

**1. 加依赖（pom.xml）**

`flowable-spring-boot-starter` 已含 Event Registry 引擎，只需再加 MQ 适配器 starter：

```xml
<!-- RabbitMQ：Event Registry 走 Spring 的 RabbitTemplate/Listener -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
<!-- 若用 Kafka 改成：spring-kafka -->
```

application.yaml 配上 MQ 连接：

```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
```

**2. 事件定义 `.event`（描述 payload 与关联键）**

放 `src/main/resources/`，文件名即 key，例如 `taskCreated.event`：

```json
{
  "key": "taskCreated",
  "name": "审批待办事件",
  "correlationParameters": [
    { "name": "bizId", "type": "string" }
  ],
  "payload": [
    { "name": "bizId",     "type": "string" },
    { "name": "taskName",  "type": "string" },
    { "name": "assignee",  "type": "string" }
  ]
}
```

`correlationParameters` = 关联键，决定消息能否唤醒到正确的流程实例。

**3. 通道定义 `.channel`（连哪个队列、收还是发）**

出站（中台→A，发待办通知）`notifyOut.channel`：

```json
{
  "key": "notifyOut",
  "name": "对A的通知出站通道",
  "channelType": "outbound",
  "type": "rabbit",
  "serializerType": "json",
  "destination": "flowable.notify.exchange",
  "routingKey": "task.created"
}
```

入站（A→中台，收回执）`replyIn.channel`：

```json
{
  "key": "replyIn",
  "name": "来自A的回执入站通道",
  "channelType": "inbound",
  "type": "rabbit",
  "deserializerType": "json",
  "queues": ["flowable.reply.queue"],
  "channelEventKeyDetection": { "fixedValue": "taskReply" }
}
```

`channelEventKeyDetection` 告诉引擎：这个队列来的消息算哪种 event（也可用 `jsonField`/`jsonPointerExpression` 从消息体里取）。

**4. 在 BPMN 里挂节点**

对应上面四类节点，在 `leave.bpmn20.xml` 风格的流程里这样写：

Send Event Task（发通知给 A，发完即走）：
```xml
<sendEventServiceTask id="notifyA" flowable:type="send-event"
    flowable:eventType="taskCreated"
    flowable:eventName="审批待办事件">
  <extensionElements>
    <flowable:eventInParameter source="${bizId}"   target="bizId"/>
    <flowable:eventInParameter source="${taskName}" target="taskName"/>
    <flowable:eventInParameter source="${assignee}" target="assignee"/>
    <flowable:channelKey>notifyOut</flowable:channelKey>
  </extensionElements>
</sendEventServiceTask>
```

Event Registry Start（A 发消息直接发起流程）：
```xml
<startEvent id="start" flowable:formFieldValidation="true">
  <extensionElements>
    <flowable:eventType>taskCreated</flowable:eventType>
    <flowable:channelKey>replyIn</flowable:channelKey>
  </extensionElements>
</startEvent>
```

Receive Event Task（流程停下等 A 回执）：用 `<receiveEventServiceTask>` 配 `eventType=taskReply`，再用 `<flowable:eventCorrelationParameter name="bizId" value="${bizId}"/>` 把回执路由回当前实例。

**5. 部署与触发**

- `.event` / `.channel` / `.bpmn20.xml` 放进 `src/main/resources/`，Spring Boot starter **启动时自动部署**（与现有 `processes/leave.bpmn20.xml` 同机制）。
- 发起流程后走到 `notifyA`，引擎自动把变量序列化成 JSON 发到 RabbitMQ exchange，A 端订阅 `flowable.notify.exchange` 即收到。
- A 处理完往 `flowable.reply.queue` 发 `taskReply`，入站通道按 `bizId` 唤醒等待的实例继续。

**6. 换 Kafka / 自定义 MQ**

- 换 Kafka：`.channel` 里 `"type": "kafka"`，`destination` 改成 topic，依赖换 `spring-kafka`——**BPMN 和 .event 一行不用动**（这正是 Event Registry 的卖点：中间件可插拔）。
- 公司用的 MQ 不在 Kafka/RabbitMQ/JMS 之列：实现 `InboundEventChannelAdapter` Bean，`.channel` 里 `"type": "custom"` 指向它。

> ⚠️ 6.8.1 开源版实践注意：
> - 开源版**无 Flowable Design 图形化建模**，上述三类文件全靠手写；可用 Docker 跑 flowable-rest 容器调试 REST，但 channel/event 建模 GUI 仍是商业版。
> - 可靠性取决于底层 MQ + 引擎事务；核心一致性流程仍建议叠加 **Outbox（方案 4）**。
> - 务必处理**幂等 / 顺序 / 死信重试**（见文末三点）。

#### 方案 6 用哪个消息中间件？

**硬约束**：Event Registry 开箱只支持 **Kafka / RabbitMQ / JMS** 三种，**RocketMQ 不在内置列表**，要用得自己写 `InboundEventChannelAdapter`。所以选型先看用不用方案6。

**按 Flowable 官方支持程度排序（6.8.1）：**

| 排名 | 中间件 | Flowable 支持情况 |
|------|--------|------|
| 🥇 并列 | **RabbitMQ** | 官方内置适配器，开箱即用，`.channel` 里 `"type":"rabbit"` 直接用 |
| 🥇 并列 | **Kafka** | 官方内置适配器，开箱即用，`"type":"kafka"`；官方文档/示例最常拿它举例 |
| 🥈 | **JMS（ActiveMQ 等）** | 官方内置适配器，`"type":"jms"`，更多面向老系统/企业内网 |
| 🥉 | **RocketMQ** | **无官方内置**，需自己实现 `InboundEventChannelAdapter` / 出站适配器 |

> 补充：商业版（Work/Orchestrate）还额外内置 **AWS SQS/SNS、Email、REST** 通道，开源版没有。
>
> RabbitMQ 与 Kafka 支持度对等（都是一级公民、配置方式一样、可互换），区别只在适配场景：论**官方示例最多、最高吞吐**选 Kafka；论**和中台通知场景最契合、上手最快**选 RabbitMQ。

| 中间件 | 模型 | 吞吐 | 强项 | 弱项 | Event Registry 内置 |
|--------|------|------|------|------|------|
| **RabbitMQ** | 队列+灵活路由(exchange) | 中（万级/s） | 路由灵活、延迟队列方便、运维简单、业务消息友好 | 海量堆积/超高吞吐不如 Kafka | ✅ |
| **Kafka** | 分区日志(topic) | 超高（十万~百万/s） | 吞吐大、可回放、按分区有序、适合事件流/审计/大数据 | 路由弱、延迟队列要自己搞、运维偏重 | ✅ |
| **RocketMQ** | topic+tag | 高 | **事务消息**、顺序消息、堆积能力强，贴合「流程库与业务库一致」 | 生态以阿里系为主；**Event Registry 不内置** | ❌（需自定义） |
| **JMS/ActiveMQ** | 队列/主题(JMS 标准) | 低~中 | Java 标准 API、老系统/企业内网常见 | 吞吐与扩展性最弱 | ✅ |

**怎么选：**

- **本仓库当前场景（审批待办通知、结果回写、状态变更）→ 首选 RabbitMQ。** 量级不大、要灵活路由（不同业务方/不同 routingKey）、延迟队列（超时提醒）方便，运维成本低，且 Event Registry 内置——和上面方案6 示例完全对得上。
- **流程事件流、审计、要回放、对接大数据 → Kafka。** 例如把所有流程节点流转事件打成事件流给数仓/监控，Kafka 的高吞吐+可回放是刚需。
- **核心诉求是「流程状态与业务库强一致、消息绝对不丢」→ RocketMQ 的事务消息最省事**，但要接受它不在内置列表（得自定义适配器，或走方案2手写监听器 + 方案4 Outbox）。用 Kafka/RabbitMQ 也能不丢，只是要自己叠 **Outbox（方案 4）**。
- **JMS/ActiveMQ**：除非对接已有老 JMS 系统，否则新项目不推荐。

> 一句话：本仓库这种中台通知场景，**直接上 RabbitMQ + 方案6 Event Registry**（内置支持、配置即用、路由灵活）；以后要做「流程事件流/审计/大数据」再并行引入 **Kafka**；对资金类等强一致核心流程，无论用哪个都叠 **Outbox** 保证不丢不重。

---

## 三、综合对比

| 维度 | REST 同步 | MQ | BPMN 消息事件 | Outbox+MQ | Webhook | Event Registry |
|------|----------|-----|--------------|-----------|---------|----------------|
| 耦合度 | 高 | 低 | 中 | 低 | 中 | 低 |
| 实时性 | 强 | 准实时 | 取决传输 | 准实时 | 准实时 | 准实时 |
| 可靠性 | 弱(需自兜底) | 强 | 中 | 最强 | 弱~中 | 强(依赖底层MQ) |
| 一致性 | 强一致 | 最终一致 | 最终一致 | 最终一致(保证不丢) | 最终一致 | 最终一致 |
| 削峰/堆积 | 无 | 强 | 无 | 强 | 无 | 强 |
| 运维成本 | 低 | 中高 | 低 | 高 | 低 | 中(MQ+引擎配置) |
| 流程可视化 | 无 | 无 | 有 | 无 | 无 | 有 |
| 与引擎集成度 | 低 | 低 | 高 | 低 | 低 | 最高(官方原生) |
| 中间件可插拔 | — | 需改代码 | — | 需改代码 | — | 改channel即可 |

---

## 四、选型建议（典型组合，而非二选一）

实际生产里通常是**混合**：

1. **A → 流程中台**：用同步 REST（发起、提交需要立即拿到结果），本仓库的 flowable-rest 直接满足。
2. **流程中台 → A**：用 **MQ 异步通知**（待办、结果回写、状态变更），解耦削峰。
3. **核心一致性流程**：在第 2 步基础上叠加 **Outbox 模式**，保证流程状态与消息不丢不重。
4. **流程需等待业务事件**：用 **BPMN Message Event**，由 A 通过 REST/MQ 触发引擎继续。

> 如果团队用 Flowable 6.6+ 且希望「收发消息」成为流程模型的标准部分、避免自己写监听器+序列化样板，推荐用 **方案 6 Event Registry** 统一承载第 2、4 步：用 Channel 对接 Kafka/RabbitMQ，用 Send Event Task 发给 A、用 Message/Receive 事件接收 A 的回执。它是 MQ + BPMN 消息事件两种思路的官方融合，可插拔且可视化。对一致性极高的核心流程，仍建议叠加 Outbox。

无论哪种，务必处理三件事：**幂等**（消息可能重复）、**顺序**（同一流程实例的消息别乱序）、**死信/重试**（消费失败兜底）。
