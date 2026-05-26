package org.example.flowabledemo.controller;

import org.example.flowabledemo.dto.TaskVO;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/flow")
public class FlowController {

    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;

    public FlowController(RuntimeService runtimeService, TaskService taskService, HistoryService historyService) {
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.historyService = historyService;
    }

    // 1. 启动请假流程
    @GetMapping("/start")
    public String startProcess(@RequestParam String applyUser) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("applyUser", applyUser);
        ProcessInstance instance = runtimeService.startProcessInstanceByKey("leave-process", vars);
        return "流程启动成功，实例ID：" + instance.getId();
    }

    // 2. 查询待办任务
    @GetMapping("/task/list")
    public List<TaskVO> taskList(@RequestParam String assignee) {
        List<Task> tasks = taskService.createTaskQuery()
            .taskAssignee(assignee)
            .list();

        // 转成自定义VO返回 ✅ 解决懒加载报错
        List<TaskVO> result = new ArrayList<>();
        for (Task task : tasks) {
            TaskVO vo = new TaskVO();
            vo.setId(task.getId());
            vo.setName(task.getName());
            vo.setAssignee(task.getAssignee());
            vo.setProcessInstanceId(task.getProcessInstanceId());
            vo.setCreateTime(task.getCreateTime());
            result.add(vo);
        }
        return result;
    }

    // 3. 完成任务（审批）
    // taskId是/flow/task/list接口中返回的id
    @GetMapping("/task/complete")
    public String completeTask(@RequestParam String taskId) {
        taskService.complete(taskId);
        return "任务已完成：" + taskId;
    }

    // 4. 我的已办任务
    @GetMapping("/history/my-done")
    public List<HistoricTaskInstance> myDone(@RequestParam String assignee) {
        return historyService.createHistoricTaskInstanceQuery()
            .taskAssignee(assignee)
            .finished()
            .orderByHistoricTaskInstanceEndTime().desc()
            .list();
    }

    // 5. 某流程的审批轨迹
    // 调： /flow/history/my-done?assignee=zhangsan 或 /flow/history/my-done?assignee=leader → 拿到已办列表
    // 每条记录里拿 processInstanceId → 调 /flow/history/activity → 展示审批链
    @GetMapping("/history/activity")
    public List<HistoricActivityInstance> activity(@RequestParam String procId) {
        return historyService.createHistoricActivityInstanceQuery()
            .processInstanceId(procId)
            .orderByHistoricActivityInstanceStartTime().asc()
            .list();
    }
}