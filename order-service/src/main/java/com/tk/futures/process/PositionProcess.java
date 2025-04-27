package com.tk.futures.process;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.tk.futures.model.AsyncMessageItems;
import com.tk.futures.model.MethodAnnotation;
import com.tk.futures.model.UserData;
import com.tk.futures.service.ProcessService;
import com.tx.common.entity.Position;
import com.tx.common.entity.TradePrice;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

@Service
public class PositionProcess extends BaseProcess {

    @MethodAnnotation("getAssetsList")
    public AsyncMessageItems getAssetsList(UserData userData) {
        LinkedList<Position> positions = userData.getPositions();
        Map<Integer, TradePrice> tradePriceMap = ProcessService.tradePrices;
        ArrayList<JSONObject> positionVoList = new ArrayList<>();
        BigDecimal totalUnRealizedAmount = BigDecimal.ZERO;
        for (Position position : positions) {
            JSONObject item = JSON.parseObject(JSON.toJSONString(position));
            positionVoList.add(item);
            TradePrice tradePrice = tradePriceMap.get(position.getMarketId());
            if (tradePrice != null) {
                item.put("tagPrice", tradePrice.getPrice());
                BigDecimal unRealizedAmount;
                if (position.isBuy()) {
                    unRealizedAmount = position.getVolume().add(position.getPendingCloseVolume()).multiply(tradePrice.getPrice().subtract(position.getOpenPrice()));
                } else {
                    unRealizedAmount = position.getVolume().add(position.getPendingCloseVolume()).multiply(position.getOpenPrice().subtract(tradePrice.getPrice()));
                }
                item.put("unRealizedAmount", unRealizedAmount);
                totalUnRealizedAmount = totalUnRealizedAmount.add(unRealizedAmount);
            }
        }
        HashMap<String, Object> ret = new HashMap<>();
        ret.put("positionList", positionVoList);
        ret.put("accounts", userData.getAccounts());
        ret.put("unRealizedAmount", totalUnRealizedAmount);
        response(ret);
        return null;
    }

}
