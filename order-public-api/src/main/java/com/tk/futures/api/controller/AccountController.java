package com.tk.futures.api.controller;

import com.alibaba.fastjson.JSONObject;
import com.tk.futures.api.async.AsyncService;
import com.tx.common.entity.Transfer;
import com.tx.common.message.request.KafkaRequest;
import com.tx.common.service.TransferService;
import com.tx.common.vo.R;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.math.BigDecimal;

@RestController
@RequestMapping("/account")
public class AccountController {

    private final TransferService transferService;

    private final AsyncService asyncService;

    public AccountController(TransferService transferService, AsyncService asyncService) {
        this.transferService = transferService;
        this.asyncService = asyncService;
    }

    @RequestMapping("/addBalance")
    public DeferredResult<R> addBalance(@RequestBody JSONObject params) {
        String transferId = params.getString("transferId");
        Long coinId = params.getLong("coinId");
        Long uid = params.getLong("uid");
        BigDecimal amount = params.getBigDecimal("amount");

        if (transferId == null || coinId == null || uid == null || amount == null) {
            R<Object> fail = R.fail(400, "futures.params.error");
            DeferredResult<R> deferredResult = new DeferredResult<>(5000L, R.fail(500, "futures.time_out"));
            deferredResult.setResult(fail);
            return deferredResult;
        }
        Transfer transfer = new Transfer();
        transfer.setTransferId(transferId);
        transfer.setCoinId(coinId);
        transfer.setAmount(amount);
        transfer.setUid(uid);
        transfer.setTxid(0L);
        transfer.setStatus(Transfer.STATUS_INIT);
        transfer.setCtime(new java.util.Date());
        transfer.setMtime(new java.util.Date());
        Transfer dbTransfer = transferService.lambdaQuery().eq(Transfer::getTransferId, transfer.getTransferId()).one();
        if (dbTransfer != null) {
            DeferredResult<R> deferredResult = new DeferredResult<>(5000L, R.fail(500, "futures.time_out"));
            if (dbTransfer.isIn() || dbTransfer.isSuccess()) {
                deferredResult.setResult(R.success(true)); // 重复操作
            } else {
                deferredResult.setResult(R.success(false));
            }
            return deferredResult;
        }
        transferService.save(transfer);
        KafkaRequest request = new KafkaRequest("transfer", transfer, uid);
        request.setMethod("transfer");
        request.setUid(uid);
        return asyncService.send(request);
    }

}
