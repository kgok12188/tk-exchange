package com.tk.match.engine.matcher;

import com.tk.match.engine.BookOrder;
import com.tk.match.engine.OrderBook;
import com.tk.match.engine.PriceLevel;

/**
 * 买单 taker 吃卖盘（ask）、卖单 taker 吃买盘（bid）时，沿对手盘推进价位的方式。
 * 限价单需 {@link #canCrossSpread}；市价单沿同一路径扫盘，但不调用穿价判断。
 */
enum OppositeSideWalk {

    /**
     * 买 taker：对手为 ask，从最优卖价由低到高。
     */
    ASK_FOR_BUY_TAKER {
        @Override
        Long firstPrice(OrderBook book) {
            return book.firstAskPriceTicks();
        }

        @Override
        boolean canCrossSpread(long takerPriceTicks, long oppositePriceTicks) {
            return takerPriceTicks >= oppositePriceTicks;
        }

        @Override
        PriceLevel level(OrderBook book, long priceTicks) {
            return book.levelAtAsk(priceTicks);
        }

        @Override
        void removeLevelIfEmpty(OrderBook book, long priceTicks) {
            book.removeAskLevelIfEmpty(priceTicks);
        }

        @Override
        Long nextOppositePrice(OrderBook book, long priceTicks) {
            return book.nextAskAfter(priceTicks);
        }
    },
    /**
     * 卖 taker：对手为 bid，从最优买价由高到低。
     */
    BID_FOR_SELL_TAKER {
        @Override
        Long firstPrice(OrderBook book) {
            return book.firstBidPriceTicks();
        }

        @Override
        boolean canCrossSpread(long takerPriceTicks, long oppositePriceTicks) {
            return takerPriceTicks <= oppositePriceTicks;
        }

        @Override
        PriceLevel level(OrderBook book, long priceTicks) {
            return book.levelAtBid(priceTicks);
        }

        @Override
        void removeLevelIfEmpty(OrderBook book, long priceTicks) {
            book.removeBidLevelIfEmpty(priceTicks);
        }

        @Override
        Long nextOppositePrice(OrderBook book, long priceTicks) {
            return book.nextBidBelow(priceTicks);
        }
    };

    abstract Long firstPrice(OrderBook book);

    /**
     * 限价 taker 是否仍可与该对手价位成交。
     */
    abstract boolean canCrossSpread(long takerPriceTicks, long oppositePriceTicks);

    abstract PriceLevel level(OrderBook book, long priceTicks);

    abstract void removeLevelIfEmpty(OrderBook book, long priceTicks);

    abstract Long nextOppositePrice(OrderBook book, long priceTicks);

    static OppositeSideWalk forTaker(BookOrder taker) {
        return taker.isSideBuy() ? ASK_FOR_BUY_TAKER : BID_FOR_SELL_TAKER;
    }
}
