package com.tk.futures.model;

import com.tk.protocol.dto.TradingSettle;
import com.tx.common.entity.Account;
import com.tx.common.entity.Order;
import com.tx.common.message.PersistenceBatch;
import lombok.Data;

import java.util.*;
import java.util.function.Consumer;


@Data
public class UserTradingBook {

    private Long uid;
    private Map<Long, Order> orders = new LinkedHashMap<>();
    private Map<Long, Account> accounts = new LinkedHashMap<>();

    private transient Map<Long, Order> changeOrders = new LinkedHashMap<>();
    private transient HashSet<Long> removeOrderIds = new HashSet<>();
    private transient Map<Long, Account> changeAccounts = new LinkedHashMap<>();
    private transient HashSet<Long> removeAccountIds = new HashSet<>();

    private Map<Long, TradingSettle> unTradingSettles = new LinkedHashMap<>();

    private static final String USDT = "USDT";

    public UserTradingBook(Long uid, List<Order> orders, List<Account> accounts) {
        this.uid = uid;
        for (Order order : orders) {
            this.orders.put(order.getId(), order);
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
        Account copyAccount = account.clone();
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
        Order copyOrder = order.clone();
        copyOrder.setTxid(copyOrder.getTxid() == null ? 0 : copyOrder.getTxid() + 1);
        changeOrders.put(orderId, copyOrder);
        return copyOrder;
    }

    public void removeOrder(Order order) {
        removeOrderIds.add(order.getId());
    }

    public void removeOrder(long orderId) {
        removeOrderIds.add(orderId);
    }


    /**
     * 提交本次事务的内存变更，并将需要持久化的订单/账户记录组装为 PersistenceBatchList 交给 consumer。
     * <p>
     * - 先基于 changeOrders/changeAccounts 和 removeId 集合更新已提交状态（orders/accounts）。
     * - 再将本次有效变更组装为批次（订单批次 + 账户批次），通过 consumer 传递给上层。
     * - 最后清空本次事务的增量缓存，准备下一次指令。
     */
    public void commit(Consumer<PersistenceBatchList> consumer) {
        PersistenceBatchList batches = new PersistenceBatchList();

        // 订单变更批次
        if (!changeOrders.isEmpty()) {
            PersistenceBatch orderBatch = new PersistenceBatch();
            orderBatch.setType(PersistenceBatch.Type.ORDER.getValue());
            List<Object> orderMessages = new ArrayList<>();
            changeOrders.forEach((id, order) -> {
                if (removeOrderIds.contains(id)) {
                    orders.remove(id);
                } else {
                    orders.put(id, order);
                    orderMessages.add(order);
                }
            });
            if (!orderMessages.isEmpty()) {
                orderBatch.setMessages(orderMessages);
                batches.add(orderBatch);
            }
        } else {
            // 即便没有新增/修改订单，也要处理删除标记
            removeOrderIds.forEach(orders::remove);
        }

        // 账户变更批次
        if (!changeAccounts.isEmpty()) {
            PersistenceBatch accountBatch = new PersistenceBatch();
            accountBatch.setType(PersistenceBatch.Type.ACCOUNT.getValue());
            List<Object> accountMessages = new ArrayList<>();
            changeAccounts.forEach((id, account) -> {
                if (removeAccountIds.contains(id)) {
                    accounts.remove(id);
                } else {
                    accounts.put(id, account);
                }
                accountMessages.add(account);
            });
            if (!accountMessages.isEmpty()) {
                accountBatch.setMessages(accountMessages);
                batches.add(accountBatch);
            }
        } else {
            removeAccountIds.forEach(accounts::remove);
        }

        // 清理本次事务的增量缓存
        changeOrders = new LinkedHashMap<>();
        removeOrderIds = new HashSet<>();
        changeAccounts = new LinkedHashMap<>();
        removeAccountIds = new HashSet<>();

        // 将本次事务的持久化批次交给上层
        if (consumer != null && !batches.isEmpty()) {
            consumer.accept(batches);
        }
    }

    public void rollback() {
        changeOrders = new LinkedHashMap<>();
        removeOrderIds = new HashSet<>();
        changeAccounts = new LinkedHashMap<>();
        removeAccountIds = new HashSet<>();
    }

    public void addTradingSettle(Long offset, TradingSettle tradingSettle) {
        unTradingSettles.put(offset, tradingSettle);
    }

    public void removeTradingSettle(Long offset) {
        unTradingSettles.remove(offset);
    }

}





