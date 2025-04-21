package com.tx.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("worker_order_group")
public class WorkerOrderGroup {
    private Integer id;
    private String groupName;
    private java.util.Date ctime;
}
