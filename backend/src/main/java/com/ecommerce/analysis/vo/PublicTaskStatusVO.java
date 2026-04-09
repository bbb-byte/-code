package com.ecommerce.analysis.vo;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 公网补充任务状态。
 */
@Data
public class PublicTaskStatusVO {

    private String taskId;

    private String taskType;

    private boolean running;

    private double progress;

    private String status;

    private String message;

    private String log;

    private Long startedAt;

    private Long finishedAt;

    private Map<String, Object> result = new HashMap<>();
}
