package com.spring.gateway.controller;

import com.spring.gateway.dto.ModelBillingFailureRequest;
import com.spring.gateway.dto.ReserveModelRequest;
import com.spring.gateway.dto.SettleModelRequest;
import com.spring.gateway.service.BillingService;
import com.spring.gateway.service.ModelBillingFailureService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 单次模型请求计费接口。
 *
 * @author hanbing
 * @since 2026-10-13
 */
@RestController
@RequestMapping("/internal/billing/requests")
@RequiredArgsConstructor
public class InternalBillingController {
    private final BillingService billingService;
    private final ModelBillingFailureService failures;

    @PostMapping("/failure")
    public Map<String, String> failure(@Valid @RequestBody ModelBillingFailureRequest request) {
        return Map.of("status", failures.report(request));
    }

    @PostMapping("/reserve")
    public Map<String, BigDecimal> reserve(@Valid @RequestBody ReserveModelRequest request) {
        return Map.of("reservedPoints", billingService.reserveRequest(request));
    }

    @PostMapping("/settle")
    public Map<String, String> settle(@Valid @RequestBody SettleModelRequest request) {
        return Map.of("status", billingService.settleRequest(request));
    }
}
