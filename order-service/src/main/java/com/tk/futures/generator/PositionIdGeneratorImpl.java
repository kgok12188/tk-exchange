package com.tk.futures.generator;

import com.tx.common.entity.Position;
import com.tx.common.service.PositionService;
import org.redisson.api.RIdGenerator;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

@Service
public class PositionIdGeneratorImpl implements PositionIdGenerator {

    private final RIdGenerator positionIdGenerator;

    public PositionIdGeneratorImpl(RedissonClient redissonClient, PositionService positionService) {
        positionIdGenerator = redissonClient.getIdGenerator("position_id_get");
        Position dbPosition = positionService.lambdaQuery().orderByDesc(Position::getId).last("limit 1").one();
        if (dbPosition == null) {
            positionIdGenerator.tryInit(0, 1);
        } else {
            positionIdGenerator.tryInit(0, dbPosition.getId() + 100000);
        }
    }

    public long nextId() {
        return positionIdGenerator.nextId();
    }

}
