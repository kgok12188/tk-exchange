
use futures;
drop table if exists worker_order_group;
CREATE TABLE worker_order_group
(
    id         INT AUTO_INCREMENT PRIMARY KEY,
    group_name VARCHAR(50) NOT NULL,
    ctime      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    unique key (group_name)
);

insert into worker_order_group(group_name, ctime)
values ('g1', now()),
       ('g2', now()),
       ('g3', now());

drop table if exists worker_order_group_jvm;
CREATE TABLE worker_order_group_jvm
(
    id               INT AUTO_INCREMENT PRIMARY KEY,
    group_name       VARCHAR(50) NOT NULL,
    ctime            TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    jvm_id           VARCHAR(200) NOT NULL default '',
    unique key (group_name,jvm_id)
);
insert into worker_order_group_jvm(group_name, ctime, jvm_id)
values ('g1', now(), 'jvm1');