package com.spring.gateway.workspace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spring.gateway.workspace.entity.Message;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * messages 表的 Mapper。
 *
 * @author hanbing
 * @since 2026-10-03
 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    @Select("""
            SELECT id FROM messages FORCE INDEX (ix_messages_session_id)
            WHERE session_id = #{sessionId} ORDER BY id DESC LIMIT 1
            """)
    Long selectLatestId(@Param("sessionId") String sessionId);

    @Select("""
            SELECT * FROM messages FORCE INDEX (ix_messages_session_id)
            WHERE session_id = #{sessionId} AND id <= #{anchor} AND id < #{before}
            ORDER BY id DESC LIMIT #{limit}
            """)
    List<Message> selectHistoryBackward(@Param("sessionId") String sessionId, @Param("before") long before,
                                        @Param("anchor") long anchor, @Param("limit") int limit);

    @Select("""
            SELECT * FROM messages FORCE INDEX (ix_messages_session_id)
            WHERE session_id = #{sessionId} AND id <= #{anchor} AND id >= #{fromId}
            ORDER BY id ASC LIMIT #{limit}
            """)
    List<Message> selectHistoryForward(@Param("sessionId") String sessionId, @Param("fromId") long fromId,
                                       @Param("anchor") long anchor, @Param("limit") int limit);
}
