package com.tx.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/*
drop table if exists transfer;
CREATE TABLE transfer (
      id INT(20) PRIMARY KEY,
      uid INT(20) NOT NULL comment '用户uid',
      transferId VARCHAR(50) NOT NULL comment '转账id',
      coinId INT(20) NOT NULL,
      amount DECIMAL(16,16) NOT NULL,
      txid   BIGINT(20) NOT NULL,
      status int not null comment '0 进行中 1 成功 2 失败 4 处理中',
      type int not null comment '0 转入 1 转出',
      ctime TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
      mtime TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
* */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName(value = "`transfer`")
public class Transfer {

    public static final int TYPE_IN = 0;
    public static final int TYPE_OUT = 1;

    public static final int STATUS_INIT = 0;
    public static final int STATUS_PENDING = 4;
    public static final int STATUS_OK = 1;
    public static final int STATUS_ERROR = 2;
    public static final int STATUS_ERROR_NOT_ENOUGH = 3;

    @TableId(value = "id", type = IdType.AUTO)
    @ApiModelProperty(value = "自增id")
    private Long id;
    private Long uid;
    private String transferId;
    private int type; // 0 in 1 out
    private int status;
    private Long coinId;
    private BigDecimal amount;
    private Long txid; // 版本号
    private java.util.Date ctime;
    private java.util.Date mtime;

    public boolean isSuccess() {
        return status == STATUS_OK;
    }

    public boolean isIn() {
        return type == TYPE_IN;
    }

}
