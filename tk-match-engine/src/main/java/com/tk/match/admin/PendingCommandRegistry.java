package com.tk.match.admin;

import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 维护 HTTP 请求与 Raft log 执行结果之间的关联。
 *
 * <p>流程：
 * <ol>
 *   <li>AdminController 生成 UUID，调用 {@link #register} 注册一个 CompletableFuture</li>
 *   <li>SBE 命令带 uuidHigh/uuidLow 进入 Raft log</li>
 *   <li>MatchClusteredService.onSessionMessage 处理完毕后调用 {@link #tryComplete}，唤醒 HTTP 线程</li>
 * </ol>
 *
 * <p>若命令并非通过 HTTP 发起（uuidHigh == 0 && uuidLow == 0），
 * {@link #tryComplete} 会静默忽略，不会产生副作用。
 */
@Component
public class PendingCommandRegistry {

    private final ConcurrentHashMap<String, CompletableFuture<AdminCommandResult>> pending =
            new ConcurrentHashMap<>();

    public CompletableFuture<AdminCommandResult> register(String uuidKey) {
        CompletableFuture<AdminCommandResult> future = new CompletableFuture<>();
        pending.put(uuidKey, future);
        return future;
    }

    public void tryComplete(String uuidKey, AdminCommandResult result) {
        CompletableFuture<AdminCommandResult> future = pending.remove(uuidKey);
        if (future != null) {
            future.complete(result);
        }
    }

    public void tryFail(String uuidKey, String errorMessage) {
        CompletableFuture<AdminCommandResult> future = pending.remove(uuidKey);
        if (future != null) {
            future.complete(new AdminCommandResult(false, errorMessage));
        }
    }
}
