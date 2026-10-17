package com.spring.gateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spring.gateway.entity.Work;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * works 表的 Mapper。
 *
 * @author hanbing
 * @since 2026-10-04
 */
@Mapper
public interface WorkMapper extends BaseMapper<Work> {

    /**
     * 锁定项目行并查询。
     *
     * @param id 项目 ID
     * @return 项目行，不存在时为 null
     */
    @org.apache.ibatis.annotations.Options(useCache = false, flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    @Select("SELECT * FROM works WHERE id = #{id} FOR UPDATE")
    Work selectForUpdate(@Param("id") String id);

    @org.apache.ibatis.annotations.Update("UPDATE works SET size_bytes = #{bytes}, internal_bytes = #{internal} WHERE id = #{id}")
    int updateStorage(@Param("id") String id, @Param("bytes") long bytes, @Param("internal") long internal);
}
