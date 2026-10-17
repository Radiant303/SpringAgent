package com.spring.gateway.service;

import com.spring.gateway.common.BizException;
import com.spring.gateway.common.SearchProviders;
import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.dto.SearchUsageEvent;
import com.spring.gateway.entity.PointsHold;
import com.spring.gateway.entity.PointsLedger;
import com.spring.gateway.entity.SearchUsageRecord;
import com.spring.gateway.entity.Session;
import com.spring.gateway.mapper.PointsLedgerMapper;
import com.spring.gateway.mapper.SearchUsageRecordMapper;
import com.spring.gateway.mapper.SessionMapper;
import com.spring.gateway.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * 搜索次数计费。
 *
 * @author hanbing
 * @since 2026-10-17
 */
@Service
@RequiredArgsConstructor
public class SearchBillingService {
    private final UserMapper userMapper;
    private final SessionMapper sessionMapper;
    private final SearchUsageRecordMapper searchUsageRecordMapper;
    private final PointsLedgerMapper pointsLedgerMapper;
    private final SystemSettingService systemSettingService;
    private final SnowflakeIdGenerator idGenerator;

    @Transactional
    public void chargeLocal(SearchUsageEvent event) {
        charge(event, false);
    }

    @Transactional
    public void chargeNative(PointsHold hold, Long webSearches) {
        if (webSearches == null) {
            return;
        }
        if (hold == null) {
            throw new BizException(400, "计费请求不能为空");
        }
        charge(new SearchUsageEvent("native:" + hold.getRequestId(), "native", "native", webSearches,
                hold.getUserId(), hold.getSessionId(), hold.getTurnId(), hold.getRequestId(), hold.getAgentName()), true);
    }

    private void charge(SearchUsageEvent event, boolean nativeUsage) {
        if (event == null || event.userId() == null || event.userId() <= 0) {
            throw new BizException(400, "搜索事件用户非法");
        }
        BigDecimal balance = userMapper.selectBalanceForUpdate(event.userId());
        if (balance == null) {
            throw new BizException(404, "用户不存在");
        }
        validate(event);
        Session session = sessionMapper.selectById(event.sessionId());
        if (session != null && !Objects.equals(session.getUserId(), event.userId())) {
            throw new BizException(409, "搜索事件会话归属冲突");
        }
        SearchUsageRecord previous = searchUsageRecordMapper.selectById(event.eventId());
        if (previous != null) {
            verifyReplay(previous, event);
            validateOrigin(event, nativeUsage);
            return;
        }
        validateOrigin(event, nativeUsage);
        BigDecimal unitPoints = decimal(systemSettingService.getSearchPoints(event.provider()));
        if (unitPoints.signum() < 0) {
            throw new BizException(400, "搜索单价不能为负数");
        }
        BigDecimal costPoints = decimal(unitPoints.multiply(BigDecimal.valueOf(event.searches())));
        SearchUsageRecord record = new SearchUsageRecord();
        record.setEventId(event.eventId());
        record.setSource(event.source());
        record.setProvider(event.provider());
        record.setUserId(event.userId());
        record.setSessionId(event.sessionId());
        record.setTurnId(event.turnId());
        record.setRequestId(event.requestId());
        record.setAgentName(event.agentName());
        record.setSearches(event.searches());
        record.setUnitPoints(unitPoints);
        record.setCostPoints(costPoints);
        record.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        try {
            searchUsageRecordMapper.insert(record);
        } catch (DuplicateKeyException exception) {
            SearchUsageRecord concurrent = searchUsageRecordMapper.selectForUpdate(event.eventId());
            if (concurrent == null) {
                throw exception;
            }
            verifyReplay(concurrent, event);
            return;
        }
        if (costPoints.signum() == 0) {
            return;
        }
        BigDecimal after = balance.subtract(costPoints);
        userMapper.updateBalance(event.userId(), after);
        PointsLedger ledger = new PointsLedger();
        ledger.setId(idGenerator.nextId());
        ledger.setUserId(event.userId());
        ledger.setChangeAmount(costPoints.negate());
        ledger.setBalanceAfter(after);
        ledger.setType(PointsLedger.TYPE_SEARCH);
        ledger.setRefId(event.eventId());
        ledger.setReason("搜索 " + event.provider() + " × " + event.searches());
        ledger.setCreatedAt(record.getCreatedAt());
        pointsLedgerMapper.insert(ledger);
    }

    private static void validate(SearchUsageEvent event) {
        text(event.eventId(), 64, false, "事件 ID");
        text(event.sessionId(), 36, false, "会话 ID");
        text(event.turnId(), 64, false, "轮次 ID");
        text(event.requestId(), 64, true, "请求 ID");
        text(event.agentName(), 64, true, "代理名称");
        if (event.searches() == null || event.searches() < 0) {
            throw new BizException(400, "搜索次数非法");
        }
    }

    private static void validateOrigin(SearchUsageEvent event, boolean nativeUsage) {
        if (nativeUsage) {
            if (!"native".equals(event.source()) || !"native".equals(event.provider())
                    || event.requestId() == null || !event.eventId().equals("native:" + event.requestId())) {
                throw new BizException(400, "原生搜索事件非法");
            }
        } else if (!"local".equals(event.source()) || event.provider() == null
                || !SearchProviders.isSupported(event.provider()) || event.searches() != 1L) {
            throw new BizException(400, "本地搜索事件非法");
        }
    }

    private static void text(String value, int maxLength, boolean nullable, String name) {
        if (value == null && nullable) {
            return;
        }
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new BizException(400, name + "非法");
        }
    }

    private static BigDecimal decimal(BigDecimal value) {
        if (value == null) {
            throw new BizException(400, "搜索积分不能为空");
        }
        try {
            BigDecimal result = value.setScale(6, RoundingMode.UNNECESSARY);
            if (result.precision() > 20) {
                throw new BizException(400, "搜索积分超出存储范围");
            }
            return result;
        } catch (ArithmeticException exception) {
            throw new BizException(400, "搜索积分最多六位小数");
        }
    }

    private static void verifyReplay(SearchUsageRecord previous, SearchUsageEvent event) {
        if (!Objects.equals(previous.getEventId(), event.eventId())
                || !Objects.equals(previous.getSource(), event.source())
                || !Objects.equals(previous.getProvider(), event.provider())
                || !Objects.equals(previous.getUserId(), event.userId())
                || !Objects.equals(previous.getSessionId(), event.sessionId())
                || !Objects.equals(previous.getTurnId(), event.turnId())
                || !Objects.equals(previous.getRequestId(), event.requestId())
                || !Objects.equals(previous.getAgentName(), event.agentName())
                || !Objects.equals(previous.getSearches(), event.searches())) {
            throw new BizException(409, "同一搜索事件的计费数据不一致");
        }
    }
}
