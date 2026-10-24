package com.spring.gateway.workspace.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.spring.gateway.common.BizException;
import com.spring.gateway.workspace.entity.Message;
import com.spring.gateway.workspace.entity.Session;
import com.spring.gateway.identity.entity.User;
import com.spring.gateway.workspace.entity.Work;
import com.spring.gateway.workspace.mapper.MessageMapper;
import com.spring.gateway.workspace.mapper.SessionMapper;
import com.spring.gateway.identity.mapper.UserMapper;
import com.spring.gateway.workspace.mapper.WorkMapper;
import com.spring.gateway.engine.client.EngineClient;
import com.spring.gateway.settings.service.SystemSettingService;
import com.spring.gateway.storage.service.FileStorageService;
import com.spring.gateway.storage.service.UserStorageService;
import com.spring.gateway.workspace.service.PageMappingService;
import com.spring.gateway.workspace.service.WorkService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 项目（work）业务。
 *
 * @author hanbing
 * @since 2026-10-04
 */
@Service
@RequiredArgsConstructor
public class WorkServiceImpl implements WorkService {

    /** 默认项目名。 */
    public static final String DEFAULT_WORK_NAME = "默认项目";

    private final WorkMapper workMapper;
    private final SessionMapper sessionMapper;
    private final MessageMapper messageMapper;
    private final UserMapper userMapper;
    private final EngineClient engineClient;
    private final SystemSettingService systemSettingService;
    private final FileStorageService fileStorageService;
    private final UserStorageService userStorageService;
    private final PageMappingService pageMappingService;

    @Value("${app.data-root}")
    private String dataRoot;

    @Value("${app.work-max-works:5}")
    private int maxWorks;

    /**
     * 查询当前用户的项目及存储占用。
     *
     * @param userId 用户 ID
     * @return work 列表，按创建时间倒序
     */
    public List<Work> list(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new BizException(404, "用户不存在");
        userStorageService.snapshot(user);
        return workMapper.selectList(new LambdaQueryWrapper<Work>()
                .eq(Work::getUserId, userId)
                .orderByDesc(Work::getCreatedAt)
                .orderByDesc(Work::getId));
    }

    /**
     * 新建 work；项目数量或用户存储用量达到上限时抛 409。
     *
     * @param userId 用户 ID
     * @param name   项目名
     * @return 新建的 work
     * @throws BizException 超出数量上限或存储配额（409）
     */
    @Transactional
    public Work create(Long userId, String name) {
        User user = requireLockedUser(userId);
        Long count = workMapper.selectCount(new LambdaQueryWrapper<Work>().eq(Work::getUserId, userId));
        if (count >= maxWorks) {
            throw new BizException(409, "项目数量已达上限（" + maxWorks + "个），请先删除");
        }
        long used = userStorageService.snapshot(user).usedBytes();
        long quota = user.getQuotaBytes() == null ? Long.MAX_VALUE : user.getQuotaBytes();
        if (used >= quota) {
            throw new BizException(409, "存储配额已用尽（" + used + "/" + quota + " 字节），请联系管理员调整");
        }
        return insert(userId, name, false);
    }

    /**
     * 确保用户存在默认 work，没有则创建；幂等，供内部接口与新建会话使用
     *
     * @param userId 用户 ID
     * @return 默认 work
     */
    @Transactional
    public Work ensureDefault(Long userId) {
        requireLockedUser(userId);
        Work existing = workMapper.selectOne(new LambdaQueryWrapper<Work>()
                .eq(Work::getUserId, userId)
                .eq(Work::getIsDefault, true));
        if (existing != null) {
            pageMappingService.ensureForWork(existing.getId());
            return existing;
        }
        return insert(userId, DEFAULT_WORK_NAME, true);
    }

    /**
     * 归属校验后取 work，不存在或属于他人一律 404
     *
     * @param userId 用户 ID
     * @param workId work ID
     * @return work 行
     * @throws BizException 不存在或越权（404）
     */
    public Work getOwned(Long userId, String workId) {
        Work row = workMapper.selectById(workId);
        if (row == null || !userId.equals(row.getUserId())) {
            throw new BizException(404, "项目不存在");
        }
        return row;
    }

    /**
     * 内部接口用：按 ID 取 work，不存在即 404（不做归属校验，调用方带 userId 入参自行校验）
     *
     * @param workId work ID
     * @return work 行
     * @throws BizException 不存在（404）
     */
    public Work getForInternal(String workId) {
        Work row = workMapper.selectById(workId);
        if (row == null) {
            throw new BizException(404, "项目不存在: " + workId);
        }
        return row;
    }

    /**
     * 删除项目、会话、消息及工作区目录，保留用量和积分账目。
     *
     * @param userId 用户 ID
     * @param workId work ID
     * @throws BizException 越权（404）、有活跃会话（409）、引擎不可达（503）
     */
    @Transactional
    public void hardDelete(Long userId, String workId) {
        requireLockedUser(userId);
        Work work = workMapper.selectForUpdate(workId);
        if (work == null || !userId.equals(work.getUserId())) {
            throw new BizException(404, "项目不存在");
        }
        int active;
        try {
            active = engineClient.activeSessionsInWork(workId);
        } catch (Exception e) {
            throw new BizException(503, "引擎不可达，拒绝删除");
        }
        if (active > 0) {
            throw new BizException(409, "项目下的会话正在使用中（页面挂载着该项目或有任务在跑），"
                    + "请先在页面切换到其他项目，或关闭对应标签页后再删除");
        }
        List<Session> sessions = sessionMapper.selectList(
                new LambdaQueryWrapper<Session>().eq(Session::getWorkId, workId).last("FOR UPDATE"));
        fileStorageService.deleteByWork(workId);
        for (Session session : sessions) {
            messageMapper.delete(new LambdaQueryWrapper<Message>().eq(Message::getSessionId, session.getId()));
        }
        sessionMapper.delete(new LambdaQueryWrapper<Session>().eq(Session::getWorkId, workId));
        pageMappingService.deleteByWorkId(workId);
        userStorageService.releaseWork(requireLockedUser(userId), workId);
        workMapper.deleteById(workId);
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        deleteDirectoryRecursively(workspacePath(workId));
                    }
                });
    }

    /**
     * work 的派生工作区目录路径（路径权威在网关，引擎经内部 API 消费）
     *
     * @param workId work ID
     * @return 目录路径
     */
    public String workspacePath(String workId) {
        return dataRoot + "/works/" + workId;
    }

    public String knowledgePath(long userId) {
        return userStorageService.knowledgeRoot(userId).toString();
    }

    /**
     * 该 work 生效的单工作区上限（字节）：所属用户有覆盖值用覆盖值，否则用全局设置
     *
     * @param row work 行
     * @return 生效的上限字节数
     */
    public long effectiveWorkMaxBytes(Work row) {
        User user = userMapper.selectById(row.getUserId());
        if (user != null && user.getWorkQuotaBytes() != null) {
            return user.getWorkQuotaBytes();
        }
        return systemSettingService.getWorkMaxBytes();
    }

    /**
     * 用户的存储概览：占用合计 / 总配额 / 生效的单工作区配额。
     *
     * @param userId 用户 ID
     * @return 概览三元组
     * @throws BizException 用户不存在（404）
     */
    public StorageOverview storageOverview(long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(404, "用户不存在");
        }
        long workMax = user.getWorkQuotaBytes() != null
                ? user.getWorkQuotaBytes()
                : systemSettingService.getWorkMaxBytes();
        long used = userStorageService.snapshot(user).usedBytes();
        return new StorageOverview(used,
                user.getQuotaBytes() == null ? 0L : user.getQuotaBytes(), workMax);
    }

    /** 用户不存在（库重建后旧 token 的幽灵用户）时拒绝，避免外键异常冒成 500 */
    private User requireLockedUser(Long userId) {
        User user = userMapper.selectForUpdate(userId);
        if (user == null) {
            throw new BizException(404, "用户不存在");
        }
        return user;
    }

    private Work insert(Long userId, String name, boolean isDefault) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Work row = new Work();
        row.setId(UUID.randomUUID().toString());
        row.setUserId(userId);
        row.setName(name);
        row.setSizeBytes(0L);
        row.setIsDefault(isDefault);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        workMapper.insert(row);
        pageMappingService.ensureForWork(row.getId());
        return row;
    }

    private static void deleteDirectoryRecursively(String dir) {
        Path root = Path.of(dir);
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted((a, b) -> -a.compareTo(b)).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    // 目录内被占用时尽力删，残留文件不阻断库内删除
                }
            });
        } catch (IOException ignored) {
            // 目录不存在或不可读：视为已删除
        }
    }
}
