package com.spring.gateway.redemption.service;

import com.spring.gateway.dto.PageQuery;
import com.spring.gateway.dto.PageResult;
import com.spring.gateway.redemption.dto.AdminRedeemCreateRequest;
import com.spring.gateway.redemption.dto.RedeemResultView;
import com.spring.gateway.redemption.entity.RedeemCode;

import java.util.List;
import java.util.Locale;

/**
 * 兑换码业务。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public interface RedeemService {
    List<RedeemCode> generate(long actorId, AdminRedeemCreateRequest req);

    RedeemResultView redeem(long userId, String rawCode);

    void revoke(long id);

    PageResult<RedeemCode> page(String statusFilter, PageQuery q);

    List<RedeemCode> myRedemptions(long userId, int limit);

    static String normalize(String raw) {
        return raw == null ? "" : raw.replaceAll("[-\\s]", "").toUpperCase(Locale.ROOT);
    }
}
