package com.spring.gateway.workspace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spring.gateway.workspace.entity.MessageNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * message_nodes 表的 Mapper。
 *
 * @author hanbing
 * @since 2026-11-27
 */
@Mapper
public interface MessageNodeMapper extends BaseMapper<MessageNode> {

    @Select("SELECT COALESCE(MAX(version), 0) FROM message_nodes WHERE session_id = #{sessionId} AND group_id = #{groupId}")
    int maxVersion(@Param("sessionId") String sessionId, @Param("groupId") String groupId);
}
