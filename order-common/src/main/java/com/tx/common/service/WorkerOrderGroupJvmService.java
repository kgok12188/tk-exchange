package com.tx.common.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tx.common.entity.WorkerOrderGroupJvm;
import com.tx.common.mapper.WorkerOrderGroupJvmMapper;
import org.springframework.stereotype.Service;

@Service
public class WorkerOrderGroupJvmService extends ServiceImpl<WorkerOrderGroupJvmMapper, WorkerOrderGroupJvm> {

    public void updateLost(WorkerOrderGroupJvm workerOrderGroupJvm) {
        baseMapper.updateLost(workerOrderGroupJvm);
    }

    public boolean tryGet(WorkerOrderGroupJvm workerOrderGroupJvm, String newJvmId) {
        return baseMapper.tryGet(workerOrderGroupJvm.getId(), newJvmId) == 1;
    }

    public void updateLastTime(String jvmId) {
        baseMapper.updateLastTime(jvmId);
    }

    public void removeJvmId(String jvmId) {
        baseMapper.removeJvmId(jvmId);
    }
}
