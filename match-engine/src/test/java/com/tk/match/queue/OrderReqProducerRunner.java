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
 * 参数（可选，空格分隔）：bootstrapServers [symbol] [count]
 * - bootstrapServers 默认 localhost:9092
 * - symbol 默认 BTC-USDT（需与 match-engine 的 match.symbols 中一致）
 * - count 默认 5（发送 PUSH_ORDER 条数）
 */
public class OrderReqProducerRunner {

    private static final String ORDER_REQ_PREFIX = "order_req_";
    private static final String DEFAULT_BOOTSTRAP = "127.0.0.1:9092";
    private static final String DEFAULT_SYMBOL = "BTC-USDT";
    private static final int DEFAULT_COUNT = 200;

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

        String topic = ORDER_REQ_PREFIX + symbol;
        System.out.println("OrderReqProducerRunner: bootstrap=" + bootstrap + " topic=" + topic + " count=" + count);


        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrap);
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("acks", "all");

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            long baseOrderId = System.currentTimeMillis() % 1_000_000;
            long uid = 10001L;
            int i = 0;
            while (i < 1000000) {
                // 交替买卖、不同价格，便于产生成交
                boolean buy = (i % 2 == 0);
                BigDecimal price = BigDecimal.valueOf(60000 + (i % 10) * 100);
                BigDecimal volume = BigDecimal.valueOf(0.001 * (i + 1));

                OrderPayload payload = OrderPayload.builder()
                        .id(baseOrderId + i)
                        .uid(uid)
                        .shardId((int) (uid % 2))
                        .symbol(symbol)
                        .marketId(1L)
                        .side(buy ? "BUY" : "SELL")
                        .priceType("LIMIT")
                        .price(price)
                        .volume(volume)
                        .build();

                OrderCommand cmd = OrderCommand.builder()
                        .type(CommandType.PUSH_ORDER)
                        .symbol(symbol)
                        .pushPayload(payload)
                        .build();

                String json = ProtocolSerde.toJson(cmd);
                ProducerRecord<String, String> record = new ProducerRecord<>(topic, null, json);
                Future<RecordMetadata> future = producer.send(record, (m, ex) -> {
                    if (ex != null) {
                        System.err.println("Send failed: " + ex.getMessage());
                    } else {
                        System.out.println("Sent PUSH_ORDER orderId=" + payload.getId() + " " + payload.getSide() + " " + payload.getPrice() + " @ " + payload.getVolume() + " -> " + m.topic() + "-" + m.partition() + "@" + m.offset());
                    }
                });
                future.get();
                i++;
                Thread.sleep(50);
            }

            // 可选：发一笔撤单（撤销最后一笔）
            long cancelOrderId = baseOrderId + count - 1;
            OrderCommand cancelCmd = OrderCommand.builder()
                    .type(CommandType.CANCEL_ORDER)
                    .symbol(symbol)
                    .cancelPayload(CancelPayload.builder().shardId((int) (uid % 2)).orderId(cancelOrderId).uid(uid).build())
                    .build();
            String cancelJson = ProtocolSerde.toJson(cancelCmd);
            producer.send(new ProducerRecord<>(topic, null, cancelJson), (m, ex) -> {
                if (ex != null) System.err.println("Send CANCEL failed: " + ex.getMessage());
                else
                    System.out.println("Sent CANCEL_ORDER orderId=" + cancelOrderId + " -> " + m.topic() + "-" + m.partition() + "@" + m.offset());
            }).get();

            producer.flush();
            System.out.println("Done. Check match-engine logs and match_result_" + symbol + " topic.");
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
