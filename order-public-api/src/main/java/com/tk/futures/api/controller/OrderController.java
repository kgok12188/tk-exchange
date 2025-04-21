package com.tk.futures.api.controller;


import com.alibaba.fastjson.JSONObject;
import com.tk.futures.api.async.AsyncService;
import com.tk.futures.api.interceptor.LoginInterceptor;
import com.tx.common.entity.MarketConfig;
import com.tx.common.entity.Order;
import com.tx.common.entity.Position;
import com.tx.common.entity.User;
import com.tx.common.message.request.KafkaRequest;
import com.tx.common.service.MarketConfigService;
import com.tx.common.vo.R;
import io.swagger.annotations.ApiOperation;
import lombok.extern.log4j.Log4j2;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.util.Date;


@RestController
@Log4j2
@RequestMapping("/order")
public class OrderController {

    private final AsyncService asyncService;
    private final MarketConfigService marketConfigService;

    public OrderController(AsyncService asyncService, MarketConfigService marketConfigService) {
        this.asyncService = asyncService;
        this.marketConfigService = marketConfigService;
    }

    /**
     * 当前委托列表
     *
     * @param request
     * @param params
     * @return
     */
    @ApiOperation(value = "当前委托列表")
    @PostMapping(value = "/current_order_list", produces = "application/json;charset=utf-8")
    public R<Object> currentOrderList(HttpServletRequest request, HttpServletResponse response, @RequestBody JSONObject params) {
        AsyncContext asyncContext = request.startAsync(request, response);
        return null;
    }

    @ApiOperation(value = "查找历史订单")
    @PostMapping(value = "/history_order_list", produces = "application/json;charset=utf-8")
    public R<Object> historyOrderList(HttpServletRequest request, @RequestBody JSONObject params, HttpServletResponse response, ModelMap model) {
        return null;
    }

    @ApiOperation(value = "查找历史条件单")
    @PostMapping(value = "/trigger_order_list", produces = "application/json;charset=utf-8")
    public R<Object> historyTriggerOrderList(@RequestBody JSONObject params) {
        return null;
    }


    @ApiOperation(value = "设置止盈止损单")
    @PostMapping(value = "/take_profit_stop_loss", produces = "application/json;charset=utf-8")
    public R<Object> takeProfitStopLoss(@RequestBody JSONObject params) {
        return null;
    }

    @ApiOperation(value = "创建订单")
    @PostMapping(value = "/create", produces = "application/json;charset=utf-8")
    public DeferredResult<R> createOrder(@RequestBody JSONObject params) {
        Order order = params.toJavaObject(Order.class);
        order.setCtime(new Date());
        Order.PriceType priceType = Order.PriceType.fromValue(order.getPriceType());
        Order.DealType dealType = Order.DealType.fromValue(order.getDealType());
        Order.OrderSide side = Order.OrderSide.fromValue(order.getSide());
        Order.OPEN open = Order.OPEN.fromValue(order.getOpen());
        Position.PositionType positionType = Position.PositionType.fromValue(order.getPositionType());
        if (order.getUid() == null || priceType == null || dealType == null || side == null || open == null ||
                positionType == null || order.getLeverageLevel() <= 0 || order.getLeverageLevel() >= 50 || order.getUid() == 0) {
            R fail = R.fail(500, "futures.params.error");
            DeferredResult<R> deferredResult = new DeferredResult<>(5000L, R.fail(500, "futures.time_out"));
            deferredResult.setResult(fail);
            return deferredResult;
        }
        if (order.isDealTypeAmount()) { // 按照金额下单
            if (order.getAmount() == null || order.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                R<Object> fail = R.fail(500, "futures.params.error");
                DeferredResult<R> deferredResult = new DeferredResult<>(5000L, R.fail(500, "futures.time_out"));
                deferredResult.setResult(fail);
                return deferredResult;
            }
        } else { // 按照数量下单
            if (order.isOpenPosition() && (order.getVolume() == null || order.getVolume().compareTo(BigDecimal.ZERO) <= 0)) {
                R<Object> fail = R.fail(500, "futures.params.error");
                DeferredResult<R> deferredResult = new DeferredResult<>(5000L, R.fail(500, "futures.time_out"));
                deferredResult.setResult(fail);
                return deferredResult;
            }
        }
        MarketConfig marketConfig = marketConfigService.getById(order.getMarketId());
        KafkaRequest request = new KafkaRequest("createOrder", order, order.getUid());
        return asyncService.send(request);
    }

    /**
     * 查找当前未触发的止盈止损单
     *
     * @param params
     * @return
     */
    @ApiOperation(value = "查找当前未触发的止盈止损单")
    @PostMapping(value = "/take_profit_stop_loss_list", produces = "application/json;charset=utf-8")
    public R<Object> takeProfitStopLossAll(@RequestBody JSONObject params) {
        return null;
    }

    /**
     * 历史订单查询
     *
     * @param params
     * @return
     */
    @PostMapping(value = "/history_order_list_all", produces = "application/json;charset=utf-8")
    public R<Object> historyOrderListAll(@RequestBody JSONObject params) {
        return null;
    }


    @PostMapping(value = "/get_trade_all", produces = "application/json;charset=utf-8")
    public R<Object> get_trade_all(@RequestBody JSONObject params, ModelMap model) {
        User user = LoginInterceptor.getUser();
        return null;
    }

    /**
     * 获取用户当前委托和当前条件委托计数
     *
     * @param request
     * @param params
     * @param response
     * @param model
     * @return
     */
    @PostMapping(value = "/get_user_order_count", produces = "application/json;charset=utf-8")
    public R<Object> getUserOrderCount(HttpServletRequest request, @RequestBody JSONObject params, HttpServletResponse response, ModelMap model) {
        User user = LoginInterceptor.getUser();
        return null;
    }

}
