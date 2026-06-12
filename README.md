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

资料 | 说明
--- | ---
官方GitHub | https://github.com/flowable/flowable-engine
BPMN 全称 | Business Process Model and Notation（业务流程模型与符号）
CMMN 全称 | Case Management Model and Notation（案例管理模型与符号）
DMN 全称 | Decision Model and Notation（决策模型与符号）
边界事件默认都是捕获型的，且分为两种触发后的处理策略：中断型 (Interrupting)、非中断型 (Non-interrupting)。 | 
所有开始事件，都是捕获事件 | 
