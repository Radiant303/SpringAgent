package com.spring.gateway.catalog.service.impl;

import com.spring.gateway.common.BizException;
import com.spring.gateway.catalog.dto.AdminRateCardRequest;
import com.spring.gateway.catalog.entity.ModelDefinition;
import com.spring.gateway.catalog.entity.RateCard;
import com.spring.gateway.catalog.mapper.ModelDefinitionMapper;
import com.spring.gateway.catalog.mapper.RateCardMapper;
import com.spring.gateway.catalog.service.AdminRateCardService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型资费卡管理测试。
 *
 * @author hanbing
 * @since 2026-10-13
 */
class AdminRateCardServiceUnitTests {

    private final RateCardMapper rateMapper = mock(RateCardMapper.class);
    private final ModelDefinitionMapper modelMapper = mock(ModelDefinitionMapper.class);
    private final AdminRateCardService service = new AdminRateCardServiceImpl(rateMapper, modelMapper);

    private static AdminRateCardRequest request(String modelId, Boolean enabled) {
        return new AdminRateCardRequest(
                modelId, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, enabled);
    }

    @Test
    void createsByModelId() {
        ModelDefinition definition = new ModelDefinition();
        definition.setEnabled(true);
        when(modelMapper.selectById("alibaba/qwen-plus")).thenReturn(definition);
        RateCard row = service.save(request("alibaba/qwen-plus", true));
        assertEquals("alibaba/qwen-plus", row.getModelId());
        verify(rateMapper).insert(row);
    }

    @Test
    void rejectsUnknownModelId() {
        assertEquals(400, assertThrows(BizException.class,
                () -> service.save(request("missing/model", true))).getStatus());
    }

    @Test
    void protectsDefaultCard() {
        RateCard row = new RateCard();
        row.setModelId(RateCard.DEFAULT_MODEL_ID);
        when(rateMapper.selectById(RateCard.DEFAULT_MODEL_ID)).thenReturn(row);
        assertEquals(403, assertThrows(BizException.class,
                () -> service.save(request(RateCard.DEFAULT_MODEL_ID, false))).getStatus());
        assertEquals(403, assertThrows(BizException.class,
                () -> service.delete(RateCard.DEFAULT_MODEL_ID)).getStatus());
    }
}
