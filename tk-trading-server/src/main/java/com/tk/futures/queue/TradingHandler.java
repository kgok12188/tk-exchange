package com.tk.futures.queue;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.tk.futures.generator.TxIdGenerator;
import com.tk.futures.generator.TxIdGeneratorImpl;
import com.tk.futures.model.RingBufferTradingBook;
import com.tk.futures.model.UserTradingBook;
import com.tk.protocol.dto.TradingSettle;
import com.tx.common.enums.TradingCommand;
import com.tx.common.service.*;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 请求处理服务。与 MessageQueueService 配合：仅消费 REQUEST_MESSAGE，按 uid 槽位队列顺序处理。
 * 注意：ORDER_MATCH、TRADE_PRICE 已不在本服务消费；matchOrder/exceptionOrder/doLiq 保留供其他调用方（如独立消费者或 RPC）使用。
 */
@Service
public class TradingHandler {

    private final Map<Integer, RingBufferTradingBook> globalTradingBooks = new ConcurrentHashMap<>();
    private final Map<Integer, TxIdGenerator> txIdGeneratorMap = new ConcurrentHashMap<>();
    private static final Logger logger = LoggerFactory.getLogger(TradingHandler.class);
    private static final int PROCESSOR_THREAD_COUNT = Runtime.getRuntime().availableProcessors();

    private AccountService accountService;
    private UserService userService;
    private ArrayList<LinkedBlockingQueue<Runnable>> taskArray;
    private TradingConsumer tradingConsumer;
    private ApplicationContext applicationContext;
    private KafkaProducer<String, String> kafkaProducer;
    private TradeOrderService tradeOrderService;
    private PositionService positionService;
    private OrderService orderService;

    public TradingHandler() {
        init();
    }

    /**
     * 启动按 uid 槽位的工作线程与队列。
     */
    private void init() {
        taskArray = new ArrayList<>(PROCESSOR_THREAD_COUNT);
        for (int i = 0; i < PROCESSOR_THREAD_COUNT; i++) {
            taskArray.add(new LinkedBlockingQueue<>());
        }
        for (int i = 0; i < taskArray.size(); i++) {
            LinkedBlockingQueue<Runnable> queue = taskArray.get(i);
            globalTradingBooks.put(i, new RingBufferTradingBook());
            txIdGeneratorMap.put(i, new TxIdGeneratorImpl());
            new Thread(() -> {
                do {
                    try {
                        Runnable task = queue.poll(100, TimeUnit.MILLISECONDS);
                        if (task != null) {
                            task.run();
                        }
                    } catch (Exception e) {
                        logger.warn("Processor thread interrupted", e);
                    }
                } while (!queue.isEmpty());
            }, "processor-" + i).start();
        }
    }

    /**
     * 状态机处理：仅处理 REQUEST_MESSAGE 请求，按 uid 落入槽位队列，同一 uid 顺序执行。
     * 状态流转：RECEIVED(在 MQ 消费处) -> QUEUED -> PROCESSING -> COMPLETED | FAILED
     */
    public void handleMessage(JSONObject request, long offset) {
        TradingCommand command = TradingCommand.valueOf(request.getString("command"));
        switch (command) {
            case UPDATE_MARK_PRICE, UPDATE_INDEX_PRICE:
                for (int i = 0; i < taskArray.size(); i++) {
                    RingBufferTradingBook book = globalTradingBooks.get(i);
                    taskArray.get(i).add(() -> updatePrice(request, command, book));
                }
                break;
            case CANCEL_ORDER, NEW_ORDER, MATCH, TRANSFER, CREATE_USER:
                Long uid = request.getLong("uid");
                if (uid == null || uid <= 0L) {
                    return;
                }
                int slot = slot(uid);
                RingBufferTradingBook ringBufferTradingBook = globalTradingBooks.get(slot);
                taskArray.get(slot).add(() -> handleUserMessage(uid, request, offset, ringBufferTradingBook, command));
                break;
            default:
        }
    }

    private void handleUserMessage(Long uid, JSONObject request, long offset, RingBufferTradingBook ringBufferTradingBook, TradingCommand command) {
        if (ringBufferTradingBook.getOffset() >= offset) {
            return;
        }
        switch (command) {
            case CREATE_USER:
                if (ringBufferTradingBook.containsKey(uid)) {
                    return;
                }
                UserTradingBook userTradingBook = request.getJSONObject("data").toJavaObject(UserTradingBook.class);
                ringBufferTradingBook.put(uid, userTradingBook);
                break;
            case NEW_ORDER:
                break;
            case MATCH:
                TradingSettle tradingSettle = request.getJSONObject("data").toJavaObject(TradingSettle.class);
                break;
            case CANCEL_ORDER:
                break;
        }
    }

    private int slot(long uid) {
        return Math.abs((int) (Long.reverseBytes(uid) % PROCESSOR_THREAD_COUNT));
    }

    private void updatePrice(JSONObject request, TradingCommand command, RingBufferTradingBook ringBufferTradingBook) {
        JSONArray markPrices = request.getJSONArray("prices");
        for (int i = 0; i < markPrices.size(); i++) {
            JSONObject markPrice = markPrices.getJSONObject(i);
            String symbol = markPrice.getString("symbol");
            BigDecimal price = markPrice.getBigDecimal("markPrice");
            switch (command) {
                case UPDATE_MARK_PRICE:
                    ringBufferTradingBook.updateMarkPrice(symbol, price);
                    break;
                case UPDATE_INDEX_PRICE:
                    ringBufferTradingBook.updateIndexPrice(symbol, price);
                    break;
            }
        }
    }

}
