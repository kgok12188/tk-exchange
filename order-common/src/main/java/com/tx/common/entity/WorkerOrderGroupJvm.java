package com.tx.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("worker_order_group_jvm")
public class WorkerOrderGroupJvm {
    private Integer id;
    private String groupName;
    private java.util.Date ctime;
    @TableField("last_update_time")
    private java.util.Date lastUpdateTime;
    private String jvmId;
}
