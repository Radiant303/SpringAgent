package com.spring.gateway.catalog.controller;

import com.spring.gateway.common.RequiresOwner;
import com.spring.gateway.common.Result;
import com.spring.gateway.common.TimeFormat;
import com.spring.gateway.catalog.dto.AdminModelIdRequest;
import com.spring.gateway.catalog.dto.AdminRateCardRequest;
import com.spring.gateway.catalog.dto.AdminRateCardView;
import com.spring.gateway.catalog.entity.RateCard;
import com.spring.gateway.catalog.service.AdminRateCardService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 模型资费卡管理接口。
 *
 * @author hanbing
 * @since 2026-10-05
 */
@RestController
@RequestMapping("/api/admin/rate-cards")
@RequiredArgsConstructor
public class AdminRateCardController {

    private final AdminRateCardService adminRateCardService;

    @GetMapping
    public Result<List<AdminRateCardView>> list() {
        return Result.ok(adminRateCardService.list().stream().map(AdminRateCardController::toView).toList());
    }

    @RequiresOwner(message = "仅站长可管理模型资费")
    @PutMapping
    public Result<AdminRateCardView> save(@Valid @RequestBody AdminRateCardRequest req) {
        return Result.ok(toView(adminRateCardService.save(req)));
    }

    @RequiresOwner(message = "仅站长可管理模型资费")
    @PostMapping("/delete")
    public Result<Void> delete(@Valid @RequestBody AdminModelIdRequest req) {
        adminRateCardService.delete(req.modelId());
        return Result.ok(null);
    }

    private static AdminRateCardView toView(RateCard row) {
        return new AdminRateCardView(
                row.getModelId(),
                row.getInputPoints(),
                row.getCacheReadPoints(),
                row.getCacheWritePoints(),
                row.getOutputPoints(),
                row.getEnabled(),
                TimeFormat.isoUtc(row.getCreatedAt()),
                TimeFormat.isoUtc(row.getUpdatedAt()));
    }
}
