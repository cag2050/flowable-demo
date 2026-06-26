# 尽量复用 flowable-rest 的现有 API 接口 —— 方案对比

## 核心认知

本应用已内嵌 flowable-rest（`pom.xml` 引入了 `flowable-spring-boot-starter-rest`），所以 `/process-api/...` 那一整套端点**现在就是活的**。

因此「复用」不是去 HTTP 调它再包一层，而是：

> **让这套现成端点直接对外可用，只在它外面补上中台需要的横切能力（鉴权 / 多租户 / 限流）。**

逐个端点写包装方法（per-endpoint wrapper）恰恰是要避免的反模式。

---

## 做法对比

| 做法 | 复用程度 | 要写的代码 | 适合 |
|------|---------|-----------|------|
| A. 直接暴露内嵌端点 + 自定义 Security | 100% | 只写一个 Security 配置类 | 单体中台、想最快上手 |
| B. 网关在前转发 | 100% | 0 行 Java（写网关路由配置） | 引擎独立部署、标准中台架构（推荐） |
| C. 应用内通用透传代理 | 100% | 一个 catch-all 转发器（约 30 行，全端点共用） | 想留自己前缀 + 集中鉴权，又不想上网关 |

三者都是**整套复用、零 per-endpoint 代码**，区别只是横切能力放在哪。

---

## A. 直接暴露内嵌端点（改动最小）

当前 `application.yaml` 里 `flowable.rest.security.enabled: false` —— 等于端点全裸。要做中台，把它打开并换成自己的认证：

1. 保留 `flowable-spring-boot-starter-rest`，`/process-api/**` 继续由 flowable 提供。
2. 写一个**自己的 `SecurityFilterChain`** 覆盖掉 flowable 默认的 Basic Auth，接入自己的 JWT / 网关传来的身份头。所有端点一次性套上鉴权，无需逐个包装。
3. 业务系统直接调 `/process-api/runtime/process-instances` 等现成端点。

**代价**：业务方要懂引擎语义（`/process-api/...`）；鉴权是「接口级」而非「流程实例级」的细粒度隔离。

## B. 网关在前转发（标准中台做法，推荐）

flowable-rest 作为独立服务（已有 docker `flowable/flowable-rest:6.8.0`），前面放 **Spring Cloud Gateway / Nginx / APISIX**：

```
业务系统 → 网关(/wf/**) → flowable-rest(/flowable-rest/service/**)
            ├ 统一鉴权(JWT/OAuth2)
            ├ 多租户：注入 tenantId 头/路径
            ├ 限流、审计、灰度
            └ 路径重写
```

- **零 Java 代码**，端点 100% 复用，路由用配置描述。
- 引擎与中台解耦，可独立扩缩容。
- 横切能力全在网关，最干净。

这是「最大化复用现成 API」在生产中台里的标准答案。

### B 的网关选型：Spring Cloud Gateway / Nginx / APISIX 区别

三者都能做「网关在前转发」，但定位和适用团队差别很大。

#### 一句话定位

- **Spring Cloud Gateway (SCG)**：Java 应用级网关，和 Spring Boot 中台同栈、能写 Java 过滤器，逻辑跟业务代码无缝。
- **Nginx**：通用反向代理 / 负载均衡，配置文件驱动，稳、快、轻，但动态能力弱。
- **APISIX**：云原生 API 网关，基于 Nginx/OpenResty，热更新 + 丰富插件 + 控制台，偏运维平台。

#### 多维对比

| 维度 | Spring Cloud Gateway | Nginx | APISIX |
|------|---------------------|-------|--------|
| 技术栈 | Java / Spring，与中台同栈 | C，独立组件 | Lua/OpenResty，独立组件 |
| 部署形态 | 一个 Spring Boot 进程 | 系统服务 / 容器 | 进程 + etcd（存配置） |
| 配置方式 | 代码 + yaml | 改 nginx.conf 后 reload | 控制台 / Admin API **热更新**，无需重启 |
| 鉴权(JWT/OAuth2) | 写 GlobalFilter，**任意自定义逻辑** | 需 Lua 模块或外部 auth_request | 内置 jwt-auth/key-auth/oauth 插件 |
| 多租户头注入 | Java 过滤器里随便加 | 配置可加，复杂逻辑吃力 | 插件 / serverless 函数实现 |
| 限流 | 内置(Redis) | 基础 limit_req | 丰富（集群限流等） |
| 动态路由 | 需配合配置中心 | 弱（reload 才生效） | **强**（运行时增删路由） |
| 性能 | 中（JVM/Netty） | 极高 | 高（接近 Nginx） |
| 运维成本 | 低（Java 团队熟） | 低 | 中（要维护 etcd + APISIX） |
| 可观测 / 插件生态 | 依赖 Spring 生态 | 需自建 | 丰富（监控 / 灰度 / 插件市场） |

#### 针对 flowable-rest 中台的关键考量

1. **鉴权和多租户逻辑复杂度**：中台要做「调用方身份 → tenantId 注入 → 流程实例级隔离」这类有业务语义的逻辑。
   - SCG：直接在 Java 里拿用户体系、权限服务来判断，最灵活。
   - Nginx：纯配置吃力，复杂逻辑得写 Lua，不友好。
   - APISIX：插件能覆盖大部分，自定义靠 Lua 插件。
2. **路由是否经常变**：多业务方陆续接入、要动态加路由 / 灰度 → APISIX 的热更新优势明显；SCG 需配置中心；Nginx 要 reload。
3. **团队栈**：Spring Boot 团队用 SCG 学习成本几乎为零，能和中台共享代码（DTO、鉴权 SDK、异常处理）。

#### 网关选型建议

- **Spring Boot 中台 + 想快落地** → **Spring Cloud Gateway**。同栈，鉴权 / 多租户用 Java 写最顺，与中台一体化。代价是吃 JVM 资源、性能不如 Nginx 系。
- **只要简单转发 + 高性能、逻辑很少** → **Nginx**。最稳最轻，但别承载复杂中台逻辑。
- **多业务方、要平台化（动态路由 / 插件 / 控制台 / 可观测）** → **APISIX**。功能最全，但要接受多维护 etcd 和一套独立平台。

> 实务常见组合：**Nginx/APISIX 在最外层做流量入口和粗粒度防护，SCG 紧贴中台做业务级鉴权与多租户**。当前阶段单用 **Spring Cloud Gateway** 就够，不必一上来就上两层。

## C. 应用内通用透传代理（折中）

不想引网关、又想用自己的前缀和集中鉴权时，在 Spring 里写**一个** catch-all 转发器，把 `/wf/**` 原样转发到 flowable-rest，鉴权 / 租户逻辑只写一处、对所有端点生效 —— 不是每个接口写一个方法。

---

## 建议

- **单体 demo、想快** → A：保留 rest starter，只加一个 Security 配置类替换 Basic Auth，其余端点全部直接复用。
- **走向真正的多业务方中台** → B：独立部署 flowable-rest + 网关转发，复用全部端点且解耦。
- **仅在确实需要业务聚合 / 语义化**的少数场景，才用 `FlowController`（Java API）补几个自定义接口，其它一律复用现成端点。

> 相关：引擎成熟度与「嵌入 vs REST」选型见仓库内《Flowable 流程中台与业务系统 A 的消息交互方案对比》。
