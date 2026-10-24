package com.spring.gateway.storage.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.spring.gateway.identity.mapper.UserMapper;
import com.spring.gateway.workspace.mapper.WorkMapper;
import org.apache.ibatis.mapping.MappedStatement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 存储行锁查询的缓存配置测试。
 *
 * @author hanbing
 * @since 2026-10-17
 */
class StorageLockingMapperTests {
    @Test
    void lockedReadsFlushSessionCacheAndBypassSecondLevelCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(UserMapper.class);
        configuration.addMapper(WorkMapper.class);
        for (String id : new String[]{UserMapper.class.getName() + ".selectForUpdate",
                UserMapper.class.getName() + ".selectBalanceForUpdate",
                WorkMapper.class.getName() + ".selectForUpdate"}) {
            MappedStatement statement = configuration.getMappedStatement(id);
            assertFalse(statement.isUseCache(), id);
            assertTrue(statement.isFlushCacheRequired(), id);
        }
    }
}
