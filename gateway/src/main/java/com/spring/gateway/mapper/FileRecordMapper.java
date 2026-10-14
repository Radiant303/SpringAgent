package com.spring.gateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spring.gateway.entity.FileRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * files 表的 Mapper。
 *
 * @author hanbing
 * @since 2026-10-14
 */
@Mapper
public interface FileRecordMapper extends BaseMapper<FileRecord> {
}
