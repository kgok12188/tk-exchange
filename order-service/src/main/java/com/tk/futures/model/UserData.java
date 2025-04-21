package com.tk.futures.model;

import com.tx.common.entity.Account;
import com.tx.common.entity.Order;
import com.tx.common.entity.Position;
import lombok.Data;
import org.springframework.util.CollectionUtils;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Data
public class UserData {

    private Long uid;
    private LinkedList<Order> orders;
    private LinkedList<Position> positions;
    private LinkedList<Account> accounts;

    private static final String USDT = "USDT";

    public UserData(Long uid, LinkedList<Order> orders, LinkedList<Position> positions, LinkedList<Account> accounts) {
        this.uid = uid;
        this.orders = orders;
        this.positions = positions;
        this.accounts = accounts;
    }

    public void mergerAccount(Account account) {
        boolean isAdd = false;
        boolean noInclude = true;
        Iterator<Account> iterator = accounts.iterator();
        while (iterator.hasNext()) {
            Account current = iterator.next();
            if (Objects.equals(current.getCoinId(), account.getCoinId())) {
                noInclude = false;
                if (account.getTxid() > current.getTxid()) {
                    iterator.remove();
                    isAdd = true;
                    break;
                }
            }
        }
        if (isAdd || noInclude) {
            accounts.add(account);
        }
    }

    public Account getUSDTAccount() {
        for (Account account : accounts) {
            if (Objects.equals(account.getCoinName(), USDT)) {
                return account;
            }
        }
        return null;
    }

    public void mergerPosition(Position position) {
        boolean isAdd = false;
        boolean noInclude = true;
        Iterator<Position> iterator = positions.iterator();
        while (iterator.hasNext()) {
            Position current = iterator.next();
            if (Objects.equals(current.getId(), position.getId())) {
                noInclude = false;
                if (position.getTxid() > current.getTxid()) {
                    iterator.remove();
                    if (position.getStatus() == Position.Status.OPEN.value()) {
                        isAdd = true;
                    }
                    break;
                }
            }
        }
        if (isAdd || noInclude) {
            positions.add(position);
        }
    }

    public void mergerOrder(Order order) {
        boolean isAdd = false;
        boolean noInclude = true;
        Iterator<Order> iterator = orders.iterator();
        while (iterator.hasNext()) {
            Order current = iterator.next();
            if (Objects.equals(current.getId(), order.getId())) {
                noInclude = false;
                if (order.getTxid() > current.getTxid()) {
                    iterator.remove();
                    if (order.getStatus() == Order.OrderStatus.PART_DEAL.value() || order.getStatus() == Order.OrderStatus.INIT.value()) {
                        isAdd = true;
                    }
                    break;
                }
            }
        }
        if (isAdd || noInclude) {
            orders.add(order);
        }
    }

    public Position getPosition(Long positionId) {
        if (positionId == null || CollectionUtils.isEmpty(positions)) {
            return null;
        }
        for (Position position : positions) {
            if (Objects.equals(position.getId(), positionId)) {
                return position;
            }
        }
        return null;
    }

    public void removePosition(Position position) {
        for (Iterator<Position> iterator = positions.iterator(); iterator.hasNext(); ) {
            Position p = iterator.next();
            if (Objects.equals(p.getId(), position.getId())) {
                iterator.remove();
                break;
            }
        }
    }

    public List<Order> getOrderByPosition(Position position) {
        if (position == null) {
            return new LinkedList<>();
        }
        return orders.stream().filter(o -> Objects.equals(o.getPositionId(), position.getId())).collect(Collectors.toList());
    }

}
