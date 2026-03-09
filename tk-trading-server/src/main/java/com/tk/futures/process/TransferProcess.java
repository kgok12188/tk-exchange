package com.tk.futures.process;

import com.google.common.collect.Lists;
import com.tk.futures.model.AsyncMessageItems;
import com.tk.futures.model.MethodAnnotation;
import com.tk.futures.model.UserData;
import com.tx.common.entity.Account;
import com.tx.common.entity.Transfer;
import com.tx.common.message.AsyncMessageItem;
import com.tx.common.service.TransferService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

@Service
public class TransferProcess extends BaseProcess {

    @Autowired
    private TransferService transferService;


    @MethodAnnotation("transfer")
    public AsyncMessageItems run(UserData userData, Transfer transfer) {
        Account selectedAccount = null;
        for (Account account : userData.getAccounts()) {
            if (Objects.equals(account.getCoinId(), transfer.getCoinId())) {
                selectedAccount = account;
                break;
            }
        }

        transfer = transferService.getById(transfer.getId());
        if (selectedAccount == null) {
            return new AsyncMessageItems();
        }

        if (transfer.getStatus() != Transfer.STATUS_INIT) {
            return new AsyncMessageItems();
        }

        BigDecimal amount = transfer.isIn() ? transfer.getAmount() : transfer.getAmount().multiply(new BigDecimal("-1"));
        Transfer update = new Transfer();
        update.setId(transfer.getId());
        AsyncMessageItems messageItems = new AsyncMessageItems();
        if (selectedAccount.getAvailableBalance().add(amount).compareTo(BigDecimal.ZERO) < 0) {
            update.setStatus(Transfer.STATUS_ERROR_NOT_ENOUGH);
            transferService.updateById(update);
            fail(500, "NOT_ENOUGH_BALANCE");
            return new AsyncMessageItems();
        } else {
            update.setStatus(Transfer.STATUS_PENDING);
            transferService.updateById(update);
            long txId = nextTxId();
            update.setStatus(Transfer.STATUS_OK);
            // 修改账户可用余额
            selectedAccount.setAvailableBalance(selectedAccount.getAvailableBalance().add(amount));
            selectedAccount.setTxid(txId);
            selectedAccount.setMtime(new Date());
            messageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.ACCOUNT.getValue(), Lists.newArrayList(selectedAccount)));

            update.setTxid(txId);
            update.setMtime(new Date());
            messageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.TRANSFER.getValue(), Lists.newArrayList(update)));
        }
        response(true);
        return messageItems;
    }

}
