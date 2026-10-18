package com.spring.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.spring.gateway.common.BizException;
import com.spring.gateway.dto.SessionSummary;
import com.spring.gateway.entity.Message;
import com.spring.gateway.entity.Session;
import com.spring.gateway.entity.Work;
import com.spring.gateway.mapper.MessageMapper;
import com.spring.gateway.mapper.SessionMapper;
import com.spring.gateway.mapper.UserMapper;
import com.spring.gateway.mapper.WorkMapper;
import com.spring.gateway.ws.TurnStreamReader;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.HttpClientErrorException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 会话业务。
 *
 * @author hanbing
 * @since 2026-09-26
 */
@Service
@RequiredArgsConstructor
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private final SessionMapper sessionMapper;
    private final InternalStoreService internalStoreService;
    private final WorkService workService;
    private final UserMapper userMapper;
    private final WorkMapper workMapper;
    private final MessageMapper messageMapper;
    private final FileStorageService fileStorageService;
    private final UserStorageService userStorageService;
    private final EngineClient engineClient;
    private final TurnStreamReader turnStreamReader;

    /**
     * 查询当前用户未删除的会话列表，按更新时间倒序；workId 非 null 时只返回该 work 下的
     *
     * @param userId 用户 ID
     * @param workId work ID，可为 null
     * @return 会话摘要列表
     */
    public List<SessionSummary> list(Long userId, String workId) {
        LambdaQueryWrapper<Session> query = new LambdaQueryWrapper<Session>()
                .eq(Session::getUserId, userId)
                .ne(Session::getStatus, Session.STATUS_DELETED)
                .orderByDesc(Session::getUpdatedAt)
                .orderByDesc(Session::getId);
        if (workId != null) {
            query.eq(Session::getWorkId, workId);
        }
        return sessionMapper.selectList(query).stream().map(SessionService::toSummary).toList();
    }

    /**
     * 查询会话详情
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     * @return 会话摘要
     * @throws BizException 会话不存在、已删除或不属于当前用户（统一 404，不区分原因以防探测）
     */
    public SessionSummary detail(Long userId, String sessionId) {
        Session row = sessionMapper.selectOne(new LambdaQueryWrapper<Session>()
                .eq(Session::getId, sessionId)
                .eq(Session::getUserId, userId)
                .eq(Session::getStatus, Session.STATUS_ACTIVE));
        if (row == null) {
            throw new BizException(404, "会话不存在");
        }
        return toSummary(row);
    }

    /**
     * 新建会话：workId 为空时落默认 work，否则校验归属后落指定 work
     *
     * @param userId 当前用户 ID
     * @param workId work ID，可为 null
     * @return 会话摘要
     * @throws BizException work 不存在或不属于当前用户（404）
     */
    public SessionSummary create(Long userId, String workId) {
        Work work = workId == null
                ? workService.ensureDefault(userId)
                : workService.getOwned(userId, workId);
        String sessionId = UUID.randomUUID().toString();
        Session row = internalStoreService.createSession(sessionId, userId, work.getId());
        return toSummary(row);
    }

    /**
     * 删除会话、消息及专属目录，保留计费记录。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @throws BizException 会话不存在（404）、运行中（409）、引擎不可达（503）
     */
    @Transactional
    public void delete(Long userId, String sessionId) {
        var user = userMapper.selectForUpdate(userId);
        Session found = sessionMapper.selectById(sessionId);
        if (user == null || found == null || !userId.equals(found.getUserId())) {
            throw new BizException(404, "会话不存在");
        }
        Work work = workMapper.selectForUpdate(found.getWorkId());
        Session row = sessionMapper.selectOne(new LambdaQueryWrapper<Session>()
                .eq(Session::getId, sessionId).last("FOR UPDATE"));
        if (work == null || !userId.equals(work.getUserId()) || row == null
                || !userId.equals(row.getUserId()) || !Session.STATUS_ACTIVE.equals(row.getStatus())) {
            throw new BizException(404, "会话不存在");
        }
        String token = UUID.randomUUID().toString();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                turnStreamReader.closeSession(sessionId);
            }

            @Override
            public void afterCompletion(int status) {
                try {
                    engineClient.finishSessionDeletion(sessionId, userId, token, status == STATUS_COMMITTED);
                } catch (RuntimeException e) {
                    log.warn("会话删除状态释放失败: session={}", sessionId, e);
                }
            }
        });
        try {
            engineClient.prepareSessionDeletion(sessionId, userId, token);
        } catch (HttpClientErrorException.Conflict e) {
            throw new BizException(409, "会话正在运行或删除，请结束当前任务后重试");
        } catch (Exception e) {
            throw new BizException(503, "会话服务暂不可用，请稍后重试");
        }
        userStorageService.ensureCurrent(user);
        userStorageService.deleteSessionDirectory(userId, work.getId(), sessionId);
        fileStorageService.deleteBySession(userId, work.getId(), sessionId);
        messageMapper.delete(new LambdaQueryWrapper<Message>().eq(Message::getSessionId, sessionId));
        sessionMapper.update(new LambdaUpdateWrapper<Session>()
                .eq(Session::getId, sessionId)
                .set(Session::getStatus, Session.STATUS_DELETED)
                .set(Session::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC)));
    }

    /** 转换为会话摘要 */
    private static SessionSummary toSummary(Session row) {
        return new SessionSummary(
                row.getId(), row.getWorkId(), row.getTitle(), row.getCreatedAt(), row.getUpdatedAt());
    }
}
