-- 现货结算 + 资金划转 最小持久化表结构
-- 说明：基于 sql/01.sql 中已有表做精简。若已执行 01.sql，可按需删减重复建表语句。

-- 币种表（参考 01.sql，可复用）
drop table if exists coin;
CREATE TABLE coin
(
    id    INT AUTO_INCREMENT PRIMARY KEY,
    name  VARCHAR(50) NOT NULL,
    ctime TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    unique key (name)
);

-- 用户表（参考 01.sql，可复用）
drop table if exists user;
CREATE TABLE user
(
    id         INT(20) PRIMARY KEY,
    status     INT(3) NOT NULL comment '是否冻结交易',
    group_name varchar(100) default 'g1'
);

-- 账户表：每用户每币种余额
drop table if exists account;
CREATE TABLE account
(
    id                     BIGINT(20) AUTO_INCREMENT PRIMARY KEY,
    uid                    BIGINT(20)      NOT NULL,
    coin_id                BIGINT(20)      NOT NULL,
    coin_name              VARCHAR(50)     NOT NULL,
    available_balance      DECIMAL(32, 16) NOT NULL default 0,
    cross_margin_frozen    DECIMAL(32, 16) NOT NULL default 0 comment '合约用，全仓冻结；现货可为 0',
    isolated_margin_frozen DECIMAL(32, 16) NOT NULL default 0 comment '合约用，逐仓冻结；现货可为 0',
    order_frozen           DECIMAL(32, 16) NOT NULL default 0 comment '挂单冻结资金（现货可选）',
    txid                   BIGINT(20)      NOT NULL default 0 comment '版本号/乐观锁',
    ctime                  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    mtime                  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    auto_update_time       TIMESTAMP DEFAULT CURRENT_TIMESTAMP comment '数据库更新时间',
    unique key uk_uid_coin (uid, coin_id),
    key idx_coin (coin_id)
);

-- 订单表：现货订单（复用 co_order 表名，部分字段为合约预留）
drop table if exists co_order;
CREATE TABLE co_order
(
    id               BIGINT(30) PRIMARY KEY,
    uid              BIGINT(20)      NOT NULL comment '用户id',
    position_id      BIGINT(20)      NOT NULL default 0 comment '合约仓位id，现货场景为 0',
    symbol           VARCHAR(50)     NOT NULL comment '交易对，如 BTC-USDT',
    market_id        INT(10)         NOT NULL comment '交易对id',
    amount           DECIMAL(32, 16) NOT NULL comment '下单总金额（按金额下单场景）',
    price_type       INT(3)          NOT NULL comment '价格类型，LIMIT / MARKET 等',
    price            DECIMAL(32, 16) NOT NULL comment '委托价格（市价单可为参考价）',
    status           INT(5)          NOT NULL comment '0 初始化 1 部分成交 2 完全成交 3 部分成交撤销 4 撤销 5 异常',
    open             VARCHAR(10)     NOT NULL default '0' comment '合约开平仓标记，现货场景可固定 0',
    side             VARCHAR(10)     NOT NULL comment 'BUY / SELL',
    position_type    INT(3)          NOT NULL default 0 comment '仓位类型，现货为 0',
    margin           DECIMAL(32, 16) NOT NULL default 0 comment '下单占用保证金，现货可为 0',
    volume           DECIMAL(32, 16) NOT NULL default 0 comment '下单总数量',
    deal_volume      DECIMAL(32, 16) NOT NULL default 0 comment '累计成交数量',
    deal_amount      DECIMAL(32, 16) NOT NULL default 0 comment '累计成交金额',
    avg_deal_price   DECIMAL(32, 16) NOT NULL default 0 comment '平均成交价',
    fee              DECIMAL(32, 16) NOT NULL default 0 comment '手续费（现货无手续费时保持 0）',
    deal_type        INT(3)          NOT NULL default 0 comment '0 按金额下单 1 按数量下单',
    leverage_level   INT(6)          NOT NULL default 1 comment '杠杆倍数，现货为 1',
    ctime            TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    mtime            TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    cancel_time      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    cancel_order     INT(3)          NOT NULL default 0 comment '0 不取消 1 取消',
    realized_amount  DECIMAL(32, 16) NOT NULL default 0 comment '已实现盈亏（合约用，现货可为 0）',
    txid             BIGINT(20)      NOT NULL default 0,
    auto_update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP comment '数据库更新时间',
    key idx_uid_market (uid, market_id),
    key idx_status_uid (status, uid)
);

-- 成交表：现货成交记录（trade_order）
drop table if exists trade_order;
CREATE TABLE trade_order
(
    id                     BIGINT(20) AUTO_INCREMENT PRIMARY KEY,
    uid                    BIGINT(20)      NOT NULL comment '用户id',
    match_id               BIGINT(20)      NOT NULL comment '撮合id',
    order_id               BIGINT(20)      NOT NULL comment '订单id',
    side                   VARCHAR(50)     NOT NULL comment 'BUY / SELL',
    price                  DECIMAL(32, 16) NOT NULL,
    volume                 DECIMAL(32, 16) NOT NULL,
    fee                    DECIMAL(32, 16) NOT NULL default 0 comment '手续费（现货无手续费保持 0）',
    role                   VARCHAR(50)     NOT NULL comment 'TAKER / MAKER 等',
    position_before_volume DECIMAL(32, 16) NOT NULL default 0 comment '合约持仓前数量，现货可为 0',
    position_after_volume  DECIMAL(32, 16) NOT NULL default 0 comment '合约持仓后数量，现货可为 0',
    status                 INT(5)          NOT NULL comment '0 处理中 1 处理完成 2 处理失败',
    ctime                  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    mtime                  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    auto_update_time       TIMESTAMP DEFAULT CURRENT_TIMESTAMP comment '数据库更新时间',
    full_match             BOOLEAN         NOT NULL default false comment 'true: 完成了撮合',
    txid                   BIGINT(20)      NOT NULL default 0,
    unique key uk_order_match (order_id, match_id),
    key idx_uid (uid)
);

-- 转账流水表：资金划转记录（transfer）
drop table if exists transfer;
CREATE TABLE transfer
(
    id          INT(20) AUTO_INCREMENT PRIMARY KEY,
    uid         INT(20)         NOT NULL comment '用户uid',
    transfer_id VARCHAR(50)     NOT NULL comment '转账id（业务唯一）',
    coin_id     INT(20)         NOT NULL,
    amount      DECIMAL(32, 16) NOT NULL,
    txid        INT(20)         NOT NULL default 0 comment '版本号',
    status      INT             NOT NULL comment '0 进行中 1 成功 2 失败',
    type        INT             NOT NULL comment '0 转入 1 转出',
    ctime       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    mtime       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    unique key uk_transfer (transfer_id)
);

