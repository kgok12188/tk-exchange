package com.tk.futures.model;

import com.alibaba.fastjson2.JSON;
import com.tx.common.entity.Account;
import com.tx.common.entity.Order;
import com.tx.common.entity.Position;
import lombok.Data;

import java.util.*;


@Data
public class UserTradingBook {

    private Long uid;
    private Map<Long, Order> orders = new LinkedHashMap<>();
    private Map<Long, Position> positions = new LinkedHashMap<>();
    private Map<Long, Account> accounts = new LinkedHashMap<>();

    private transient Map<Long, Order> changeOrders = new LinkedHashMap<>();
    private transient HashSet<Long> removeOrderIds = new HashSet<>();
    private transient Map<Long, Position> changePositions = new LinkedHashMap<>();
    private transient HashSet<Long> removePositionIds = new HashSet<>();
    private transient Map<Long, Account> changeAccounts = new LinkedHashMap<>();
    private transient HashSet<Long> removeAccountIds = new HashSet<>();

    private static final String USDT = "USDT";

    public UserTradingBook(Long uid, LinkedList<Order> orders, LinkedList<Position> positions, LinkedList<Account> accounts) {
        this.uid = uid;
        for (Order order : orders) {
            this.orders.put(order.getId(), order);
        }
        for (Position position : positions) {
            this.positions.put(position.getId(), position);
        }
        for (Account account : accounts) {
            this.accounts.put(account.getId(), account);
        }
    }

    public Account getUSDTAccount() {
        Account usdt = getAccountByCoinName();
        return usdt == null ? null : getAccountToBuild(usdt.getId());
    }

    /**
     * 按币种名称查找账户（只读），先查 changeAccounts 再查 accounts，不含已标记删除的
     */
    private Account getAccountByCoinName() {
        for (Account a : changeAccounts.values()) {
            if (Objects.equals(a.getCoinName(), UserTradingBook.USDT)) {
                return a;
            }
        }
        for (Account a : accounts.values()) {
            if (removeAccountIds.contains(a.getId())) {
                continue;
            }
            if (Objects.equals(a.getCoinName(), UserTradingBook.USDT)) {
                return a;
            }
        }
        return null;
    }

    /**
     * 按 accountId 获取可修改的账户副本，放入 changeAccounts；语义与 getOrderToBuild / getPositionToBuild 一致
     */
    public Account getAccountToBuild(Long accountId) {
        if (removeAccountIds.contains(accountId)) {
            return null;
        }
        Account account = changeAccounts.get(accountId);
        if (account != null) {
            return account;
        }
        account = accounts.get(accountId);
        if (account == null) {
            return null;
        }
        Account copyAccount = JSON.parseObject(JSON.toJSONString(account), Account.class);
        copyAccount.setTxid(copyAccount.getTxid() == null ? 0 : copyAccount.getTxid() + 1);
        changeAccounts.put(accountId, copyAccount);
        return copyAccount;
    }

    /**
     * 按 coinId 查找账户（只读）；若需修改请用 getAccountToBuild(account.getId())
     */
    public Account getAccountByCoinId(Long coinId) {
        for (Account a : changeAccounts.values()) {
            if (Objects.equals(a.getCoinId(), coinId)) {
                return a;
            }
        }
        for (Account a : accounts.values()) {
            if (removeAccountIds.contains(a.getId())) {
                continue;
            }
            if (Objects.equals(a.getCoinId(), coinId)) {
                return a;
            }
        }
        return null;
    }

    public void removeAccount(Account account) {
        if (account != null) {
            removeAccountIds.add(account.getId());
        }
    }

    public void removeAccount(Long accountId) {
        if (accountId != null) {
            removeAccountIds.add(accountId);
        }
    }

    public Position getPositionToBuild(Long positionId) {
        if (removePositionIds.contains(positionId)) {
            return null;
        }
        Position position = changePositions.get(positionId);
        if (position != null) {
            return position;
        }
        position = positions.get(positionId);
        if (position == null) {
            return null;
        }
        Position copyPosition = JSON.parseObject(JSON.toJSONString(position), Position.class);
        copyPosition.setTxid(copyPosition.getTxid() == null ? 0 : copyPosition.getTxid() + 1);
        changePositions.put(positionId, copyPosition);
        return copyPosition;
    }

    public Order getOrderToBuild(Long orderId) {
        if (removeOrderIds.contains(orderId)) {
            return null;
        }
        Order order = changeOrders.get(orderId);
        if (order != null) {
            return order;
        }
        order = orders.get(orderId);
        if (order == null) {
            return null;
        }
        Order copyOrder = JSON.parseObject(JSON.toJSONString(order), Order.class);
        copyOrder.setTxid(copyOrder.getTxid() == null ? 0 : copyOrder.getTxid() + 1);
        changeOrders.put(orderId, copyOrder);
        return copyOrder;
    }

    public void removePosition(Position position) {
        removePositionIds.add(position.getId());
    }

    public void removePosition(Long positionId) {
        removePositionIds.add(positionId);
    }

    public void removeOrder(Order order) {
        removeOrderIds.add(order.getId());
    }

    public void removeOrder(long orderId) {
        removeOrderIds.add(orderId);
    }


    public void commit() {
        changeOrders.forEach((id, order) -> {
            if (removeOrderIds.contains(id)) {
                orders.remove(id);
                return;
            }
            orders.put(id, order);
        });
        changePositions.forEach((id, position) -> {
            if (removePositionIds.contains(id)) {
                positions.remove(id);
                return;
            }
            positions.put(id, position);
        });
        changeAccounts.forEach((id, account) -> {
            if (removeAccountIds.contains(id)) {
                accounts.remove(id);
                return;
            }
            accounts.put(id, account);
        });
    }

    public void rollback() {
        changeOrders = new LinkedHashMap<>();
        removeOrderIds = new HashSet<>();
        changePositions = new LinkedHashMap<>();
        removePositionIds = new HashSet<>();
        changeAccounts = new LinkedHashMap<>();
        removeAccountIds = new HashSet<>();
    }

}





