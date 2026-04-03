package com.tk.match.queue;

import com.tk.protocol.ProtocolSerde;
import com.tk.protocol.dto.CancelPayload;
import com.tk.protocol.dto.CommandType;
import com.tk.protocol.dto.OrderCommand;
import com.tk.protocol.dto.OrderPayload;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.Future;

/**
 * 手动运行的测试程序：向 order_req_(symbol) 推送 OrderCommand（PUSH_ORDER / CANCEL_ORDER），
 * 用于在 match-engine 已启动时验证端到端撮合与 match_result 输出。
 * <p>
 * 用法：
 * <pre>
 *   # 使用默认 localhost:9092，默认 symbol BTC-USDT，发送 5 笔限价单
 *   mvn -pl match-engine exec:java -Dexec.mainClass="com.tk.match.tools.OrderReqProducerRunner"
 *
 *   # 指定 Kafka 与 symbol
 *   mvn -pl match-engine exec:java -Dexec.mainClass="com.tk.match.tools.OrderReqProducerRunner" \
 *     -Dexec.args="172.18.0.3:9092 BTC-USDT 10"
 * </pre>
 * 参数（可选，空格分隔）：bootstrapServers [symbol] [count] [scenario]
 * - bootstrapServers 默认 localhost:9092
 * - symbol 默认 BTC-USDT（需与 match-engine 的 match.symbols 中一致）
 * - count 默认 200（发送命令条数上限）
 * - scenario 默认 mixed，可选：
 *   - limit-cross：连续可成交限价单
 *   - ioc：IOC 场景
 *   - fok：FOK 场景（可成/不可成）
 *   - post-only：POST_ONLY 场景
 *   - market：市价买卖场景
 *   - invalid：非法参数拒单场景
 *   - cancel：挂单后撤单场景
 *   - mixed：综合混合场景
 */
public class OrderReqProducerRunner {

    private static final String ORDER_REQ_PREFIX = "order_req_";
    private static final String DEFAULT_BOOTSTRAP = "127.0.0.1:9092";
    private static final String DEFAULT_SYMBOL = "BTC-USDT";
    private static final int DEFAULT_COUNT = 200000000;
    private static final int MAX_COUNT = 100_000;
    private static final String DEFAULT_SCENARIO = "mixed";

    public static void main(String[] args) {
        String bootstrap = args.length > 0 ? args[0].trim() : DEFAULT_BOOTSTRAP;
        String symbol = args.length > 1 ? args[1].trim() : DEFAULT_SYMBOL;
        int count = DEFAULT_COUNT;
        if (args.length > 2) {
            try {
                count = Integer.parseInt(args[2].trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (count <= 0) {
            count = 1;
        } else if (count > MAX_COUNT) {
            System.out.println("count exceeds max, clamp to " + MAX_COUNT + ", requested=" + count);
            count = MAX_COUNT;
        }
        String scenario = args.length > 3 ? args[3].trim().toLowerCase(Locale.ROOT) : DEFAULT_SCENARIO;

        String topic = ORDER_REQ_PREFIX + symbol;
        System.out.println("OrderReqProducerRunner: bootstrap=" + bootstrap + " topic=" + topic + " count=" + count + " scenario=" + scenario);


        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrap);
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("acks", "all");

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            long baseOrderId = (System.currentTimeMillis() / 1000) * 100000;
            long uid = 10001L;

            int sentCount = 0;
            long currentBaseOrderId = baseOrderId;
            while (sentCount < count) {
                int remainCount = count - sentCount;
                List<OrderCommand> scenarioCommands = buildScenarioCommands(scenario, symbol, uid, currentBaseOrderId, remainCount);
                if (scenarioCommands.isEmpty()) {
                    System.err.println("No commands generated for scenario=" + scenario + ". supported: limit-cross|ioc|fok|post-only|market|invalid|cancel|mixed");
                    break;
                }
                for (OrderCommand orderCommand : scenarioCommands) {
                    String json = ProtocolSerde.toJson(orderCommand);
                    ProducerRecord<String, String> record = new ProducerRecord<>(topic, null, json);
                    Future<RecordMetadata> sendFuture = producer.send(record, (metadata, exception) -> {
                        if (exception != null) {
                            System.err.println("Send failed: " + exception.getMessage() + " cmdType=" + orderCommand.getType());
                        } else {
                            String summary = summarizeCommand(orderCommand);
                            System.out.println("Sent " + summary + " -> " + metadata.topic() + "-" + metadata.partition() + "@" + metadata.offset());
                        }
                    });
                    sendFuture.get();
                    sentCount++;
                    if (sentCount >= count) {
                        break;
                    }
                    Thread.sleep(1);
                }
                currentBaseOrderId += scenarioCommands.size();
            }

            producer.flush();
            System.out.println("Done. sent=" + sentCount + ". Check match-engine logs and match_result_" + symbol + " topic.");
        } catch (Exception exception) {
            System.err.println("Error: " + exception.getMessage());
            exception.printStackTrace();
            System.exit(1);
        }
    }

    private static List<OrderCommand> buildScenarioCommands(String scenario, String symbol, long uid, long baseOrderId, int count) {
        return switch (scenario) {
            case "limit-cross" -> buildLimitCrossCommands(symbol, uid, baseOrderId, count);
            case "ioc" -> buildIocCommands(symbol, uid, baseOrderId, count);
            case "fok" -> buildFokCommands(symbol, uid, baseOrderId, count);
            case "post-only" -> buildPostOnlyCommands(symbol, uid, baseOrderId, count);
            case "market" -> buildMarketCommands(symbol, uid, baseOrderId, count);
            case "invalid" -> buildInvalidCommands(symbol, uid, baseOrderId, count);
            case "cancel" -> buildCancelCommands(symbol, uid, baseOrderId, count);
            case "mixed" -> buildMixedCommands(symbol, uid, baseOrderId, count);
            default -> List.of();
        };
    }

    private static List<OrderCommand> buildLimitCrossCommands(String symbol, long uid, long baseOrderId, int count) {
        List<OrderCommand> commands = new ArrayList<>();
        int size = Math.max(2, count);
        for (int i = 0; i < size; i++) {
            boolean buySide = (i % 2 == 0);
            BigDecimal price = buySide ? new BigDecimal("60010") : new BigDecimal("60000");
            BigDecimal volume = new BigDecimal("0.001");
            commands.add(pushCommand(symbol, uid, baseOrderId + i, buySide ? "BUY" : "SELL", "LIMIT", "GTC", price, volume, null));
        }
        return commands;
    }

    private static List<OrderCommand> buildIocCommands(String symbol, long uid, long baseOrderId, int count) {
        List<OrderCommand> commands = new ArrayList<>();
        commands.add(pushCommand(symbol, uid, baseOrderId, "SELL", "LIMIT", "GTC", new BigDecimal("60000"), new BigDecimal("0.001"), null));
        int iocCount = Math.max(1, count - 1);
        for (int i = 0; i < iocCount; i++) {
            long orderId = baseOrderId + 1 + i;
            commands.add(pushCommand(symbol, uid, orderId, "BUY", "LIMIT", "IOC", new BigDecimal("60010"), new BigDecimal("0.002"), null));
        }
        return commands;
    }

    private static List<OrderCommand> buildFokCommands(String symbol, long uid, long baseOrderId, int count) {
        List<OrderCommand> commands = new ArrayList<>();
        commands.add(pushCommand(symbol, uid, baseOrderId, "SELL", "LIMIT", "GTC", new BigDecimal("60000"), new BigDecimal("0.001"), null));
        commands.add(pushCommand(symbol, uid, baseOrderId + 1, "BUY", "LIMIT", "FOK", new BigDecimal("60000"), new BigDecimal("0.002"), null));
        int remain = Math.max(0, count - 2);
        for (int i = 0; i < remain; i++) {
            long orderId = baseOrderId + 2 + i;
            commands.add(pushCommand(symbol, uid, orderId, "BUY", "LIMIT", "FOK", new BigDecimal("60000"), new BigDecimal("0.001"), null));
        }
        return commands;
    }

    private static List<OrderCommand> buildPostOnlyCommands(String symbol, long uid, long baseOrderId, int count) {
        List<OrderCommand> commands = new ArrayList<>();
        commands.add(pushCommand(symbol, uid, baseOrderId, "SELL", "LIMIT", "GTC", new BigDecimal("60000"), new BigDecimal("0.001"), null));
        commands.add(pushCommand(symbol, uid, baseOrderId + 1, "BUY", "LIMIT_MAKER", null, new BigDecimal("60000"), new BigDecimal("0.001"), null));
        int remain = Math.max(0, count - 2);
        for (int i = 0; i < remain; i++) {
            long orderId = baseOrderId + 2 + i;
            commands.add(pushCommand(symbol, uid, orderId, "BUY", "LIMIT_MAKER", null, new BigDecimal("59900"), new BigDecimal("0.001"), null));
        }
        return commands;
    }

    private static List<OrderCommand> buildMarketCommands(String symbol, long uid, long baseOrderId, int count) {
        List<OrderCommand> commands = new ArrayList<>();
        commands.add(pushCommand(symbol, uid, baseOrderId, "SELL", "LIMIT", "GTC", new BigDecimal("60000"), new BigDecimal("0.002"), null));
        commands.add(pushCommand(symbol, uid, baseOrderId + 1, "BUY", "LIMIT", "GTC", new BigDecimal("59900"), new BigDecimal("0.002"), null));
        int remain = Math.max(1, count - 2);
        for (int i = 0; i < remain; i++) {
            long orderId = baseOrderId + 2 + i;
            if (i % 2 == 0) {
                commands.add(pushCommand(symbol, uid, orderId, "BUY", "MARKET", null, null, new BigDecimal("0.001"), new BigDecimal("120")));
            } else {
                commands.add(pushCommand(symbol, uid, orderId, "SELL", "MARKET", null, null, new BigDecimal("0.001"), null));
            }
        }
        return commands;
    }

    private static List<OrderCommand> buildInvalidCommands(String symbol, long uid, long baseOrderId, int count) {
        List<OrderCommand> commands = new ArrayList<>();
        commands.add(pushCommand(symbol, uid, baseOrderId, "BUY", "LIMIT", "GTC", BigDecimal.ZERO, new BigDecimal("0.001"), null));
        commands.add(pushCommand(symbol, uid, baseOrderId + 1, "BUY", "LIMIT", "GTC", new BigDecimal("60000"), new BigDecimal("0.00000001"), null));
        commands.add(pushCommand(symbol, uid, baseOrderId + 2, "BUY", "LIMIT", "INVALID", new BigDecimal("60000"), new BigDecimal("0.001"), null));
        int remain = Math.max(0, count - 3);
        for (int i = 0; i < remain; i++) {
            long orderId = baseOrderId + 3 + i;
            commands.add(pushCommand(symbol, uid, orderId, "SELL", "MARKET", null, null, new BigDecimal("0.00000001"), new BigDecimal("0.00000001")));
        }
        return commands;
    }

    private static List<OrderCommand> buildCancelCommands(String symbol, long uid, long baseOrderId, int count) {
        List<OrderCommand> commands = new ArrayList<>();
        int pushCount = Math.max(1, count - 1);
        for (int i = 0; i < pushCount; i++) {
            long orderId = baseOrderId + i;
            commands.add(pushCommand(symbol, uid, orderId, "BUY", "LIMIT", "GTC", new BigDecimal("59900"), new BigDecimal("0.001"), null));
        }
        long cancelOrderId = baseOrderId + pushCount - 1;
        commands.add(cancelCommand(symbol, uid, cancelOrderId));
        return commands;
    }

    private static List<OrderCommand> buildMixedCommands(String symbol, long uid, long baseOrderId, int count) {
        List<OrderCommand> commands = new ArrayList<>();
        commands.addAll(buildLimitCrossCommands(symbol, uid, baseOrderId, Math.min(4, count)));
        long nextOrderId = baseOrderId + commands.size();
        commands.addAll(buildIocCommands(symbol, uid, nextOrderId, Math.min(3, Math.max(0, count - commands.size()))));
        nextOrderId = baseOrderId + commands.size();
        commands.addAll(buildFokCommands(symbol, uid, nextOrderId, Math.min(3, Math.max(0, count - commands.size()))));
        nextOrderId = baseOrderId + commands.size();
        commands.addAll(buildPostOnlyCommands(symbol, uid, nextOrderId, Math.min(3, Math.max(0, count - commands.size()))));
        nextOrderId = baseOrderId + commands.size();
        commands.addAll(buildMarketCommands(symbol, uid, nextOrderId, Math.min(4, Math.max(0, count - commands.size()))));
        nextOrderId = baseOrderId + commands.size();
        commands.addAll(buildInvalidCommands(symbol, uid, nextOrderId, Math.min(3, Math.max(0, count - commands.size()))));
        nextOrderId = baseOrderId + commands.size();
        commands.addAll(buildCancelCommands(symbol, uid, nextOrderId, Math.min(2, Math.max(0, count - commands.size()))));
        if (commands.size() > count) {
            return new ArrayList<>(commands.subList(0, count));
        }
        return commands;
    }

    private static OrderCommand pushCommand(String symbol, long uid, long orderId, String side, String priceType, String timeInForce,
                                            BigDecimal price, BigDecimal volume, BigDecimal amount) {
        OrderPayload payload = OrderPayload.builder()
                .id(orderId)
                .uid(uid)
                .shardId((int) (uid % 2))
                .symbol(symbol)
                .marketId(1L)
                .side(side)
                .priceType(priceType)
                .timeInForce(timeInForce)
                .price(price)
                .volume(volume)
                .amount(amount)
                .createTime(System.currentTimeMillis())
                .build();
        return OrderCommand.builder()
                .type(CommandType.PUSH_ORDER)
                .symbol(symbol)
                .pushPayload(payload)
                .build();
    }

    private static OrderCommand cancelCommand(String symbol, long uid, long orderId) {
        return OrderCommand.builder()
                .type(CommandType.CANCEL_ORDER)
                .symbol(symbol)
                .cancelPayload(CancelPayload.builder()
                        .shardId((int) (uid % 2))
                        .orderId(orderId)
                        .uid(uid)
                        .build())
                .build();
    }

    private static String summarizeCommand(OrderCommand orderCommand) {
        if (orderCommand.getType() == CommandType.CANCEL_ORDER && orderCommand.getCancelPayload() != null) {
            return "CANCEL_ORDER orderId=" + orderCommand.getCancelPayload().getOrderId();
        }
        if (orderCommand.getType() == CommandType.PUSH_ORDER && orderCommand.getPushPayload() != null) {
            OrderPayload payload = orderCommand.getPushPayload();
            return "PUSH_ORDER orderId=" + payload.getId() + " side=" + payload.getSide() + " priceType=" + payload.getPriceType()
                    + " tif=" + payload.getTimeInForce() + " price=" + payload.getPrice() + " volume=" + payload.getVolume() + " amount=" + payload.getAmount();
        }
        return String.valueOf(orderCommand.getType());
    }
}
