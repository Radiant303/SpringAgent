package com.spring.gateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spring.gateway.entity.SearchBillingFailure;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 搜索计费失败事件 Mapper。
 *
 * @author hanbing
 * @since 2026-10-18
 */
@Mapper
public interface SearchBillingFailureMapper extends BaseMapper<SearchBillingFailure> {
    @Insert("""
            INSERT INTO search_billing_failures
            (id, dedup_key, raw_body, headers, event_id, provider, user_id, session_id, turn_id,
             searches, unit_points, cost_points, status, retry_count, max_retries, next_retry_at,
             error_type, error_message, created_at, updated_at)
            VALUES (#{id}, #{dedupKey}, #{rawBody}, #{headers}, #{eventId}, #{provider}, #{userId},
             #{sessionId}, #{turnId}, #{searches}, #{unitPoints}, #{costPoints}, #{status},
             #{retryCount}, #{maxRetries}, #{nextRetryAt}, #{errorType}, #{errorMessage}, #{createdAt}, #{updatedAt})
            ON DUPLICATE KEY UPDATE id = id
            """)
    int insertOnce(SearchBillingFailure row);

    @Select("SELECT COUNT(*) FROM search_billing_failures WHERE dedup_key = #{key}")
    long countByHash(@Param("key") String key);

    @Select("""
            SELECT id FROM search_billing_failures WHERE status = 'RETRYING'
            AND retry_count < max_retries AND
            ((lease_token IS NULL AND next_retry_at <= #{now}) OR lease_until <= #{now})
            ORDER BY next_retry_at, id LIMIT 20
            """)
    List<Long> findDue(@Param("now") LocalDateTime now);

    @Update("""
            UPDATE search_billing_failures SET lease_token = #{token}, lease_until = #{until},
            retry_count = retry_count + 1, updated_at = #{now}
            WHERE id = #{id} AND status = 'RETRYING' AND retry_count < max_retries AND
            ((lease_token IS NULL AND next_retry_at <= #{now}) OR lease_until <= #{now})
            """)
    int claim(@Param("id") Long id, @Param("token") String token,
              @Param("now") LocalDateTime now, @Param("until") LocalDateTime until);

    @Update("""
            UPDATE search_billing_failures SET status = 'PENDING', next_retry_at = NULL,
            lease_token = NULL, lease_until = NULL, updated_at = #{now}
            WHERE status = 'RETRYING' AND retry_count >= max_retries AND
            (lease_token IS NULL OR lease_until <= #{now})
            """)
    int recoverExhausted(@Param("now") LocalDateTime now);

    @org.apache.ibatis.annotations.Options(useCache = false,
            flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    @Select("SELECT * FROM search_billing_failures WHERE id = #{id} FOR UPDATE")
    SearchBillingFailure lockById(@Param("id") Long id);
}
