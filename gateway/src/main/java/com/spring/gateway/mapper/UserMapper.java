package com.spring.gateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spring.gateway.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * users 表的 Mapper。
 *
 * @author hanbing
 * @since 2026-09-26
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 行锁读积分余额（SELECT ... FOR UPDATE）。
     *
     * @param id 用户 ID
     * @return 当前积分余额
     */
    @Select("SELECT points_balance FROM users WHERE id = #{id} FOR UPDATE")
    BigDecimal selectBalanceForUpdate(@Param("id") Long id);

    /**
     * 写回积分余额（绝对值）；只允许在 selectBalanceForUpdate 之后同事务调用。
     *
     * @param id      用户 ID
     * @param balance 新余额
     * @return 受影响行数
     */
    @Update("UPDATE users SET points_balance = #{balance} WHERE id = #{id}")
    int updateBalance(@Param("id") Long id, @Param("balance") BigDecimal balance);

    /**
     * 增加用户存储配额。
     *
     * @param id 用户 ID
     * @param delta 增量字节数，非负
     * @param max 配额上限，单位字节
     * @return 受影响行数
     */
    @Update("UPDATE users SET quota_bytes = quota_bytes + #{delta} WHERE id = #{id}"
            + " AND #{delta} >= 0 AND #{delta} <= #{max} AND quota_bytes <= #{max} - #{delta}")
    int addQuotaBytes(@Param("id") Long id, @Param("delta") long delta, @Param("max") long max);

    /**
     * 增加单工作区存储配额。
     *
     * @param id 用户 ID
     * @param base 未设置覆盖值时的基线，单位字节
     * @param delta 增量字节数，非负
     * @param max 配额上限，单位字节
     * @return 受影响行数
     */
    @Update("UPDATE users SET work_quota_bytes = COALESCE(work_quota_bytes, #{base}) + #{delta}"
            + " WHERE id = #{id} AND #{delta} >= 0 AND #{delta} <= #{max}"
            + " AND COALESCE(work_quota_bytes, #{base}) <= #{max} - #{delta}")
    int addWorkQuotaBytes(@Param("id") Long id, @Param("base") long base,
                          @Param("delta") long delta, @Param("max") long max);

    /**
     * 锁定用户行并查询。
     *
     * @param id 用户 ID
     * @return 用户行，不存在时为 null
     */
    @Select("SELECT * FROM users WHERE id = #{id} FOR UPDATE")
    User selectForUpdate(@Param("id") Long id);

    /**
     * 增加上传占用，超额时不更新。
     *
     * @param id 用户 ID
     * @param delta 增量字节数，非负
     * @return 受影响行数；0 表示用户不存在或超额
     */
    @Update("UPDATE users SET used_bytes = used_bytes + #{delta} WHERE id = #{id}"
            + " AND #{delta} >= 0 AND used_bytes <= quota_bytes - #{delta}")
    int addUsedBytesIfUnderQuota(@Param("id") Long id, @Param("delta") long delta);

    /**
     * 扣减上传占用，最低为零。
     *
     * @param id 用户 ID
     * @param delta 扣减字节数，非负
     * @return 受影响行数
     */
    @Update("UPDATE users SET used_bytes = GREATEST(used_bytes - #{delta}, 0)"
            + " WHERE id = #{id} AND #{delta} >= 0")
    int subtractUsedBytes(@Param("id") Long id, @Param("delta") long delta);

    /**
     * 写入用户上传占用。
     *
     * @param id 用户 ID
     * @param bytes 占用字节数
     * @return 受影响行数
     */
    @Update("UPDATE users SET used_bytes = #{bytes} WHERE id = #{id}")
    int updateUsedBytes(@Param("id") Long id, @Param("bytes") long bytes);
}
