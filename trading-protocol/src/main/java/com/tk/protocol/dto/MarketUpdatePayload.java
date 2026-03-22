package com.tk.protocol.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@link CommandType#UPDATE_MARKET} 载荷：更新 {@link MarketConfig}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketUpdatePayload {

    private MarketConfig marketConfig;
    /**
     * 单调递增配置版本；重复或更旧版本应幂等忽略。
     */
    private long configVersion;
    /**
     * true：对不符合新规则的存量挂单强制撤单后再应用；false：若存在不合规则拒绝本次更新。
     */
    private boolean force;
}
