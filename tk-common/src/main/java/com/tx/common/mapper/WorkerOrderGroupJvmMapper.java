package com.tx.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tx.common.entity.WorkerOrderGroupJvm;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface WorkerOrderGroupJvmMapper extends BaseMapper<WorkerOrderGroupJvm> {

    @Update("update worker_order_group_jvm set jvm_id = '' where id = #{id} and last_update_time = #{lastUpdateTime} and jvm_id = #{jvmId}")
    void updateLost(WorkerOrderGroupJvm workerOrderGroupJvm);

    @Update("update worker_order_group_jvm set jvm_id = #{newJvmId}, last_update_time = now()  where id = #{id} and jvm_id = '' ")
    int tryGet(@Param("id") Integer id, @Param("newJvmId") String newJvmId);

    @Update("update worker_order_group_jvm set last_update_time = now() where jvm_id = #{jvmId}")
    void updateLastTime(@Param("jvmId") String jvmId);

    @Update("update worker_order_group_jvm set jvm_id = '' where jvm_id = #{jvmId}")
    void removeJvmId(@Param("jvmId") String jvmId);

}
