package com.tk.match.admin;

import com.tk.protocol.dto.MatchMarketConfig;
import com.tk.protocol.sbe.SbeEncoder;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * match-engine admin HTTP 端点，供 admin-api（或其他 HTTP 客户端）调用。
 *
 * <p>每个请求流程：
 * <ol>
 *   <li>生成 UUID，注册到 {@link PendingCommandRegistry}</li>
 *   <li>SBE 编码 admin 命令（含 uuid），通过 {@link LocalClusterClient} 推送到 Raft ingress</li>
 *   <li>阻塞等待 {@link PendingCommandRegistry#tryComplete}（由 MatchClusteredService 触发）</li>
 *   <li>超时返回 503，成功返回 200</li>
 * </ol>
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private static final int BUFFER_CAPACITY = 512;
    private static final long TIMEOUT_SECONDS = 5;

    private final PendingCommandRegistry pendingRegistry;
    private final LocalClusterClient localClusterClient;
    private final SbeEncoder sbeEncoder = new SbeEncoder();

    public AdminController(PendingCommandRegistry pendingRegistry,
                           LocalClusterClient localClusterClient) {
        this.pendingRegistry = pendingRegistry;
        this.localClusterClient = localClusterClient;
    }

    /**
     * POST /admin/openMarket
     * 上币：在 match-engine 中注册新交易对，创建对应 MatchEngine 实例。
     */
    @PostMapping("/openMarket")
    public ResponseEntity<Map<String, Object>> openMarket(@RequestBody OpenMarketRequest request) {
        UUID uuid = UUID.randomUUID();
        CompletableFuture<AdminCommandResult> future = pendingRegistry.register(uuid.toString());

        MatchMarketConfig config = MatchMarketConfig.builder()
                .symbolId(request.getSymbolId())
                .symbolName(request.getSymbolName())
                .priceScale(request.getPriceScale())
                .qtyScale(request.getQtyScale())
                .minQty(request.getMinQty())
                .minTradeQuoteAmount(request.getMinTradeQuoteAmount())
                .build();

        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(BUFFER_CAPACITY));
        int length = sbeEncoder.encodeOpenMarketCommand(
                config, request.getConfigVersion(),
                uuid.getMostSignificantBits(), uuid.getLeastSignificantBits(),
                buffer, 0);

        log.info("openMarket symbolId={} symbolName={} uuid={}", request.getSymbolId(), request.getSymbolName(), uuid);
        localClusterClient.offer(buffer, 0, length);

        return awaitResult(uuid, future);
    }

    /**
     * POST /admin/closeMarket
     * 下币：关闭交易对，拒绝后续订单（force=true 则同时撤销挂单）。
     */
    @PostMapping("/closeMarket")
    public ResponseEntity<Map<String, Object>> closeMarket(@RequestBody CloseMarketRequest request) {
        UUID uuid = UUID.randomUUID();
        CompletableFuture<AdminCommandResult> future = pendingRegistry.register(uuid.toString());

        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(BUFFER_CAPACITY));
        int length = sbeEncoder.encodeCloseMarketCommand(
                request.getSymbolId(), request.getConfigVersion(), request.isForce(),
                uuid.getMostSignificantBits(), uuid.getLeastSignificantBits(),
                buffer, 0);

        log.info("closeMarket symbolId={} force={} uuid={}", request.getSymbolId(), request.isForce(), uuid);
        localClusterClient.offer(buffer, 0, length);

        return awaitResult(uuid, future);
    }

    /**
     * POST /admin/updateMarket
     * 更新交易对配置（价格精度、最小委托量等）。
     */
    @PostMapping("/updateMarket")
    public ResponseEntity<Map<String, Object>> updateMarket(@RequestBody UpdateMarketRequest request) {
        UUID uuid = UUID.randomUUID();
        CompletableFuture<AdminCommandResult> future = pendingRegistry.register(uuid.toString());

        MatchMarketConfig config = MatchMarketConfig.builder()
                .symbolId(request.getSymbolId())
                .priceScale(request.getPriceScale())
                .qtyScale(request.getQtyScale())
                .minQty(request.getMinQty())
                .minTradeQuoteAmount(request.getMinTradeQuoteAmount())
                .build();

        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(BUFFER_CAPACITY));
        int length = sbeEncoder.encodeUpdateMarketCommand(
                config, request.getConfigVersion(), request.isForce(),
                uuid.getMostSignificantBits(), uuid.getLeastSignificantBits(),
                buffer, 0);

        log.info("updateMarket symbolId={} configVersion={} uuid={}", request.getSymbolId(), request.getConfigVersion(), uuid);
        localClusterClient.offer(buffer, 0, length);

        return awaitResult(uuid, future);
    }

    // ── private ───────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> awaitResult(UUID uuid,
                                                             CompletableFuture<AdminCommandResult> future) {
        try {
            AdminCommandResult result = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (result.isSuccess()) {
                return ResponseEntity.ok(Map.of("success", true, "message", result.getMessage()));
            }
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("success", false, "message", result.getMessage()));
        } catch (TimeoutException timeoutException) {
            pendingRegistry.tryFail(uuid.toString(), "timeout");
            log.warn("Admin command timed out uuid={}", uuid);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("success", false, "message", "command timeout"));
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            pendingRegistry.tryFail(uuid.toString(), "interrupted");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "interrupted"));
        } catch (Exception exception) {
            pendingRegistry.tryFail(uuid.toString(), exception.getMessage());
            log.error("Admin command failed uuid={}", uuid, exception);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", exception.getMessage()));
        }
    }
}
