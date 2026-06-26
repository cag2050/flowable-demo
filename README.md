### Flowable工作流

### 版本搭配
flowable-spring-boot-starter | Spring Boot | JDK版本
--- | --- | ---
【本项目使用此版本搭配】org.flowable:flowable-spring-boot-starter:6.8.x | Spring Boot 2.x | 最低支持 JDK 8
org.flowable:flowable-spring-boot-starter:7.2.0 | Spring Boot 3.2.x | 最低要求 JDK 17（建议使用 JDK 17 或 JDK 21）

### 测试步骤（使用Postman，都是Get请求）
> 针对：src/main/resources/processes/leave.bpmn20.xml
1. 启动流程 
> 生成一个待办：zhangsan 的提交申请
```
http://localhost:8080/flow/start?applyUser=zhangsan
```
2. zhangsan查看待办
```
http://localhost:8080/flow/task/list?assignee=zhangsan
```
3. zhangsan完成提交，流程走到 leader
```
http://localhost:8080/flow/task/complete?taskId=xxx
```
4. leader查看待办
```
http://localhost:8080/flow/task/list?assignee=leader
```
5. leader审批完成，流程结束
```
http://localhost:8080/flow/task/complete?taskId=xxx
```
6. zhangsan的已办任务
```
http://localhost:8080/flow/history/my-done?assignee=zhangsan
```
7. 某流程的审批轨迹
> 调： /flow/history/my-done?assignee=zhangsan 或/flow/history/my-done?assignee=leader → 拿到已办列表 → 每条记录里拿 processInstanceId → 调 /flow/history/activity → 展示审批链
```
http://localhost:8080/flow/history/activity?procId=xxx
```

### 构建my-flowable-rest镜像（之前使用的postgresql驱动，换成使用mysql驱动）：
> 背景：flowable-rest.war带Swagger UI，pom.xml引入flowable-spring-boot-starter-rest依赖不带Swagger UI；
> 想在引入flowable-spring-boot-starter-rest依赖的项目里，查看Swagger UI，开发阶段用 Docker 跑一个 flowable-rest 容器专门用来查文档和调试接口，生产环境仍然使用 Starter 嵌入部署。两者共享同一个数据库即可。
> 特别注意：需要Flowable版本号对应。
1. libs文件夹下，从 https://mvnrepository.com/artifact/com.mysql/mysql-connector-j 下载文件：mysql-connector-j-8.0.33.jar
2. 新建文件：Dockerfile
3. 构建镜像：docker build -t my-flowable-rest:6.8.0 .
4. 新建数据库：flowable-680
5. 运行镜像：
```
docker run  \
    -p 8081:8080 \
    -e SPRING_DATASOURCE_DRIVER_CLASS_NAME=com.mysql.cj.jdbc.Driver \
    -e SPRING_DATASOURCE_URL="jdbc:mysql://host.docker.internal:3306/flowable-680?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true" \
    -e SPRING_DATASOURCE_USERNAME=root \
    -e SPRING_DATASOURCE_PASSWORD=123456 \
    -e FLOWABLE_DB_SCHEMA_UPDATE=true \
    my-flowable-rest:6.8.0
```
5. 不成功，有报错；暂时先用官方镜像：flowable/flowable-rest:6.8.0

### flowable-rest 路径对应
> 1. 启动flowable-rest docker，使用端口：8081，仅用于查看接口路径和参数：docker run -p 8081:8080 flowable/flowable-rest:6.8.0
> 2. 启动本项目，使用端口：8080
> 3. docker路径中的：/flowable-rest/service/，对应本项目路径：/process-api/（BPMN流程的默认前缀）

docker接口路径 | 对应本项目接口路径
--- | ---
http://localhost:8081/flowable-rest/service/management/engine | http://localhost:8080/process-api/management/engine
http://localhost:8081/flowable-rest/service/history/historic-process-instances | http://localhost:8080/process-api/history/historic-process-instances
http://localhost:8081/flowable-rest/service/history/historic-task-instances | http://localhost:8080/process-api/history/historic-task-instances

资料 | 说明
--- | ---
官方GitHub | https://github.com/flowable/flowable-engine
BPMN 全称 | Business Process Model and Notation（业务流程模型与符号）
CMMN 全称 | Case Management Model and Notation（案例管理模型与符号）
DMN 全称 | Decision Model and Notation（决策模型与符号）
边界事件默认都是捕获型的，且分为两种触发后的处理策略：中断型 (Interrupting)、非中断型 (Non-interrupting)。 | 
所有开始事件，都是捕获事件 | 
pom.xml引入flowable-spring-boot-starter-rest依赖后，不带Swagger UI,调用引擎管理接口验证连通性（用户名和密码，在src/main/resources/application.yaml中配置） | curl -u admin:test http://localhost:8080/process-api/management/engine
Flowable REST Starter 将不同引擎的 API 隔离在独立的前缀下，不能省略前缀直接访问；BPMN流程的默认前缀：/process-api/ |
查看Flowable REST的Swagger（flowable-rest.war带Swagger UI）：docker run -p 8080:8080 flowable/flowable-rest:6.8.0 ，然后访问：http://localhost:8080/flowable-rest/docs ，认证用户名/密码：rest-admin/test |