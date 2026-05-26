package org.example.flowabledemo.dto;

import lombok.Data;
import java.util.Date;

@Data
public class TaskVO {
    private String id;          // 任务ID
    private String name;        // 任务名称
    private String assignee;    // 审批人
    private String processInstanceId; // 流程实例ID
    private Date createTime;    // 创建时间
}
