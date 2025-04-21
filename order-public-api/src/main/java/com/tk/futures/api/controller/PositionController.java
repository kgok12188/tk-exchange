package com.tk.futures.api.controller;

import com.alibaba.fastjson.JSONObject;
import com.tk.futures.api.async.AsyncService;
import com.tx.common.message.request.KafkaRequest;
import com.tx.common.vo.R;
import io.swagger.annotations.ApiOperation;
import lombok.extern.log4j.Log4j2;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


@RestController
@Log4j2
@RequestMapping("/position")
public class PositionController {


    private final AsyncService asyncService;

    public PositionController(AsyncService asyncService) {
        this.asyncService = asyncService;
    }

    /**
     * 变更持仓保证金
     *
     * @param params
     * @return
     */
    @ApiOperation(value = "变更持仓保证金")
    @PostMapping(value = "/change_position_margin", produces = "application/json;charset=utf-8")
    public R<Object> changePositionMargin(@RequestBody JSONObject params) {
        return null;
    }

    /**
     * 持仓/资产列表
     *
     * @param params
     * @return
     */
    @ApiOperation(value = "持仓/资产列表")
    @PostMapping(value = "/get_assets_list", produces = "application/json;charset=utf-8")
    public DeferredResult<R> getAssetsList(@RequestBody JSONObject params) {
        return asyncService.send(new KafkaRequest("getAssetsList", null, params.getLong("uid")));
    }

    /**
     * 持仓/资产 总值
     * 当前持仓和挂单
     *
     * @param request
     * @param response
     * @param model
     * @return
     */
    @PostMapping(value = "/get_assets_total", produces = "application/json;charset=utf-8")
    public R<Object> getAssetsList(HttpServletRequest request, HttpServletResponse response, ModelMap model) {
        return null;
    }

    @PostMapping("history_position_list")
    @ApiOperation(value = "历史仓位")
    public R<Object> historyPositionList(@RequestBody JSONObject params) {
        return null;
    }


    @PostMapping("closeAll")
    @ApiOperation(value = "一键平仓")
    public R<Object> closeAll(HttpServletRequest request) {
        return null;
    }

}
