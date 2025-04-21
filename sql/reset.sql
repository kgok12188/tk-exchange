use futures;


delete from account where id > 0;
delete from futures.user where id > 0;
delete from futures.account where id > 0;
delete from trade_order where match_id > 0;
delete from co_order where id > 0;
delete from co_position where id > 0;


insert into user(id,status,group_name) values (1000, 1,'g1');


insert into account(uid,coin_id,coin_name,available_balance,cross_margin_frozen,isolated_margin_frozen,order_frozen,txid,ctime,mtime)
    values (1000, 1,'USDT', 1000000,0,0,0,0,now(),now());

update account set available_balance = 1000000 ,order_frozen = 0, cross_margin_frozen = 0, account.isolated_margin_frozen  = 0 where id = 1;
