package com.spring.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.spring.gateway.common.BizException;
import com.spring.gateway.common.ResourceLimits;
import com.spring.gateway.dto.AdminRateCardRequest;
import com.spring.gateway.entity.ModelDefinition;
import com.spring.gateway.entity.RateCard;
import com.spring.gateway.mapper.ModelDefinitionMapper;
import com.spring.gateway.mapper.RateCardMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 模型资费卡管理。
 *
 * @author hanbing
 * @since 2026-10-05
 */
@Service
@RequiredArgsConstructor
public class AdminRateCardService {

    private final RateCardMapper rateCardMapper;
    private final ModelDefinitionMapper modelDefinitionMapper;

    public List<RateCard> list() {
        List<RateCard> rows = rateCardMapper.selectList(new LambdaQueryWrapper<RateCard>()
                .orderByAsc(RateCard::getModelId));
        rows.sort((a, b) -> Boolean.compare(
                !RateCard.DEFAULT_MODEL_ID.equals(a.getModelId()),
                !RateCard.DEFAULT_MODEL_ID.equals(b.getModelId())));
        return rows;
    }

    public RateCard save(AdminRateCardRequest req) {
        ResourceLimits.points(req.inputPoints(), false, "无缓存输入费率");
        ResourceLimits.points(req.cacheReadPoints(), false, "缓存读取费率");
        ResourceLimits.points(req.cacheWritePoints(), false, "缓存写入费率");
        ResourceLimits.points(req.outputPoints(), false, "输出费率");
        String normalizedId = req.modelId().trim();
        validateModelId(normalizedId);
        RateCard row = rateCardMapper.selectById(normalizedId);
        boolean created = row == null;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (created) {
            row = new RateCard();
            row.setModelId(normalizedId);
            row.setCreatedAt(now);
        }
        if (RateCard.DEFAULT_MODEL_ID.equals(normalizedId) && Boolean.FALSE.equals(req.enabled())) {
            throw new BizException(403, "兜底资费卡不可停用");
        }
        apply(row, req);
        row.setUpdatedAt(now);
        try {
            if (created) {
                rateCardMapper.insert(row);
            } else {
                rateCardMapper.updateById(row);
            }
        } catch (DuplicateKeyException e) {
            throw new BizException(409, "模型资费卡已存在: " + normalizedId);
        }
        return row;
    }

    public void delete(String modelId) {
        RateCard row = requireRow(modelId);
        if (RateCard.DEFAULT_MODEL_ID.equals(row.getModelId())) {
            throw new BizException(403, "兜底资费卡不可删除");
        }
        rateCardMapper.deleteById(modelId);
    }

    private void validateModelId(String modelId) {
        if (RateCard.DEFAULT_MODEL_ID.equals(modelId)) {
            return;
        }
        ModelDefinition definition = modelDefinitionMapper.selectById(modelId);
        if (definition == null && rateCardMapper.selectById(modelId) == null) {
            throw new BizException(400, "模型不存在: " + modelId);
        }
    }

    private RateCard requireRow(String modelId) {
        RateCard row = rateCardMapper.selectById(modelId);
        if (row == null) {
            throw new BizException(404, "资费卡不存在");
        }
        return row;
    }

    private static void apply(RateCard row, AdminRateCardRequest req) {
        row.setInputPoints(req.inputPoints());
        row.setCacheReadPoints(req.cacheReadPoints());
        row.setCacheWritePoints(req.cacheWritePoints());
        row.setOutputPoints(req.outputPoints());
        row.setEnabled(req.enabled() == null || req.enabled());
    }
}
