drop table if exists coin;
CREATE TABLE coin
(
    id    INT AUTO_INCREMENT PRIMARY KEY,
    name  VARCHAR(50) NOT NULL,
    ctime TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    unique key (name)
);

insert into coin(id, name)
values (1, 'USDT'),
       (2, 'BTC'),
       (3, 'ETH');

drop table if exists user;
CREATE TABLE user
(
    id         INT(20) PRIMARY KEY,
    status     INT(3) NOT NULL comment '是否冻结交易',
    group_name varchar(100) default 'g1'
);

drop table if exists market_config;
create table market_config
(
    id           INT(20) auto_increment PRIMARY KEY,
    sell_coin_id INT(20)         NOT NULL comment '卖方coin',
    buy_coin_id  INT(20)         NOT NULL comment '买方coin',
    symbol       varchar(200)    not null comment '交易币对BTC-USDT',
    name         varchar(200)    not null comment '交易名称E-BTC-USDT',
    title        varchar(200) comment '标题：永续BTC-USDT',
    img          varchar(200) comment 'logo',
    maker_rate   decimal(38, 18) not null comment '手续费费率',
    taker_rate   decimal(38, 18) not null comment '手续费费率',
    liq_rate     decimal(38, 18) not null comment '维持保证金率',
    num_min      decimal(38, 18) not null comment '单笔最小委托量',
    num_max      decimal(38, 18) not null comment '单笔最大委托量',
    price_scale  int(10)         not null comment '价格小数位',
    num_scale    int(10)         not null comment '数量小数位',
    `sort`       int(10)      default 0 comment '排序列',
    status       int(3)       default 1 comment '状态,0禁用,1启用',
    ctime        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    mtime        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    match_type   varchar(100) default 'cfd' comment '撮合模式',
    json_config  text comment '撮合配置',
    unique key (sell_coin_id, buy_coin_id),
    unique key (symbol)
) comment '交易市场配置表';

insert into market_config(id, sell_coin_id, buy_coin_id, symbol, name, title, img, maker_rate, taker_rate, liq_rate,
                          num_min, num_max, price_scale, num_scale, `sort`, status, ctime, mtime, match_type,
                          json_config)
values (1, 2, 1, 'BTC-USDT', 'E-BTC-USDT', '永续BTC-USDT', '', '0.0005', '0.0005', '0.0006',
        '0', '1000', 2, 16, 0, 1, now(), now(), 'cfd', '{"url":"wss://fstream.binance.com/ws","params":"btcusdt@trade"}');


insert into market_config(id, sell_coin_id, buy_coin_id, symbol, name, title, img, maker_rate, taker_rate, liq_rate,
                          num_min, num_max, price_scale, num_scale, `sort`, status, ctime, mtime, match_type,
                          json_config)
values (2, 3, 1, 'ETH-USDT', 'E-ETH-USDT', '永续ETH-USDT', '', '0.0005', '0.0005', '0.0006',
        '0', '1000', 2, 16, 0, 1, now(), now(), 'cfd', '{"url":"wss://fstream.binance.com/ws","params":"ethusdt@trade"}');



drop table if exists account;
CREATE TABLE account
(
    id                     INT(20) AUTO_INCREMENT PRIMARY KEY,
    uid                    INT(20)         NOT NULL,
    coin_id                INT(20)         NOT NULL,
    coin_name              VARCHAR(50)     NOT NULL,
    available_balance      DECIMAL(32, 16) NOT NULL,
    cross_margin_frozen    DECIMAL(32, 16) NOT NULL,
    isolated_margin_frozen DECIMAL(32, 16) NOT NULL,
    order_frozen           DECIMAL(32, 16) NOT NULL,
    txid                   BIGINT(20)      NOT NULL,
    ctime                  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    mtime                  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    unique key (uid, coin_id),
    key (coin_id)
);

alter table account
    add column auto_update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP comment '数据库更新时间';

drop table if exists co_order;
CREATE TABLE co_order
(
    id               bigint(30) PRIMARY KEY,
    uid              bigint(20)      NOT NULL comment '用户id',
    position_id      bigint(20)      NOT NULL default 0 comment '仓位id',
    symbol           VARCHAR(50)     NOT NULL,
    market_id        int(10)         not null,
    amount           DECIMAL(32, 16) NOT NULL,
    price_type       int(3)          not null,
    price            DECIMAL(32, 16) NOT NULL,
    status           int(5)          not null comment '0 初始化 1 部分成交 2 完全成交 3 部分成交撤销 4 撤销 5 异常',
    open             varchar(10)     not null comment '0 开仓 1 平仓',
    side             varchar(10)     not null comment '0 buy 1 sell',
    position_type    int(3)          not null comment '0 分仓 1 合仓',
    margin           DECIMAL(32, 16) NOT NULL default 0 comment '下单占用保证金',
    ctime            TIMESTAMP                DEFAULT CURRENT_TIMESTAMP,
    mtime            TIMESTAMP                DEFAULT CURRENT_TIMESTAMP,
    completed_time   TIMESTAMP                DEFAULT CURRENT_TIMESTAMP,
    cancel_time      TIMESTAMP                DEFAULT CURRENT_TIMESTAMP,
    cancel_order     int(3)          not null default 0 comment '0 不取消 1 取消',
    realized_amount  DECIMAL(32, 16) NOT NULL default 0 comment '已实现盈亏',
    txid             INT(20)         not null default 0,
    auto_update_time TIMESTAMP                DEFAULT CURRENT_TIMESTAMP comment '数据库更新时间',
    key (uid, market_id),
    key (status, uid)
);

alter table co_order
    add column volume DECIMAL(32, 16) NOT NULL default 0;
alter table co_order
    add column deal_volume DECIMAL(32, 16) NOT NULL default 0;
alter table co_order
    add column deal_amount DECIMAL(32, 16) NOT NULL default 0;
alter table co_order
    add column avg_deal_price DECIMAL(32, 16) NOT NULL default 0;
alter table co_order
    add column fee DECIMAL(32, 16) NOT NULL default 0;
alter table co_order
    add column deal_type int(3) not null comment '0 按照amount下单 1 按照 volume 下单,amount 是锁定金额';
alter table co_order
    add column leverage_level int(6) not null comment '杠杆倍数';


drop table if exists trade_order;
CREATE TABLE trade_order
(
    id                     bigint(20) AUTO_INCREMENT PRIMARY KEY,
    uid                    bigint(20)      NOT NULL comment '用户id',
    match_id               bigint(20)      NOT NULL comment '撮合id',
    order_id               bigint(20)      NOT NULL comment '订单id',
    side                   varchar(50)     NOT NULL,
    price                  DECIMAL(32, 16) NOT NULL,
    volume                 DECIMAL(32, 16) NOT NULL,
    fee                    DECIMAL(32, 16) NOT NULL,
    role                   varchar(50)     NOT NULL,
    position_before_volume DECIMAL(32, 16) NOT NULL default 0,
    position_after_volume  DECIMAL(32, 16) NOT NULL default 0,
    status                 int(5)          not null comment '0 处理中 1 处理完成 2 处理失败',
    ctime                  TIMESTAMP                DEFAULT CURRENT_TIMESTAMP,
    mtime                  TIMESTAMP                DEFAULT CURRENT_TIMESTAMP,
    auto_update_time       TIMESTAMP                DEFAULT CURRENT_TIMESTAMP comment '数据库更新时间',
    remove_match           boolean         not null default false comment 'true 完成了撮合',
    txid                   INT(20)         not null default 0,
    unique key (order_id, match_id),
    key (uid)
);

drop table if exists co_position;
CREATE TABLE co_position
(
    id                   INT(20) PRIMARY KEY,
    uid                  INT(20)         NOT NULL comment '用户uid',
    symbol               VARCHAR(50)     NOT NULL comment '交易对 BTC-USDT, ETH-USDT',
    market_id            int(10)         not null comment '交易对id',
    volume               DECIMAL(32, 16) NOT NULL comment '持仓数量',
    close_volume         DECIMAL(32, 16) NOT NULL comment '已平仓数量',
    pending_close_volume DECIMAL(32, 16) NOT NULL comment '等待平仓数量',
    fee                  DECIMAL(32, 16) NOT NULL comment '手续费',
    open_price           DECIMAL(32, 16) NOT NULL comment '开仓价格',
    close_price          DECIMAL(32, 16) NOT NULL comment '平仓价格',
    hold_amount          DECIMAL(32, 16) NOT NULL comment '持仓保证金',
    realized_amount      DECIMAL(32, 16) NOT NULL comment '已实现盈亏',
    status               int(3)          not null comment '1 未完成 0 已完成',
    leverage_level       int(6)          not null comment '最后一笔订单的杠杆倍数',
    side                 varchar(10)     not null default 'BUY' comment 'BUY 多 SELL 空',
    position_type        int(3)          not null,
    ctime                TIMESTAMP                DEFAULT CURRENT_TIMESTAMP,
    mtime                TIMESTAMP                DEFAULT CURRENT_TIMESTAMP comment '业务代码的更新时间',
    auto_update_time     TIMESTAMP                DEFAULT CURRENT_TIMESTAMP comment '数据库更新时间',
    txid                 INT(20)         not null default 0,
    liq_order_id         bigint(20)               default 0 comment '爆仓订单id,不爆仓是空',
    key (uid, market_id),
    key (ctime),
    key (mtime),
    key (status)
);


drop table if exists transfer;
CREATE TABLE transfer
(
    id          INT(20) AUTO_INCREMENT PRIMARY KEY,
    uid         INT(20)         NOT NULL comment '用户uid',
    transfer_id VARCHAR(50)     NOT NULL comment '转账id',
    coin_id     INT(20)         NOT NULL,
    amount      DECIMAL(32, 16) NOT NULL,
    txid        INT(20)         NOT NULL,
    status      int             not null comment '0 进行中 1 成功 2 失败',
    type        int             not null comment '0 转入 1 转出',
    ctime       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    mtime       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    unique key (transfer_id)
);









