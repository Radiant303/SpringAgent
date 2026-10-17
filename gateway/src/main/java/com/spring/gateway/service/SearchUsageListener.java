package com.spring.gateway.service;

import com.spring.gateway.config.SearchRabbitConfig;
import com.spring.gateway.dto.SearchUsageEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 搜索用量事件监听。
 *
 * @author hanbing
 * @since 2026-10-17
 */
@Component
@RequiredArgsConstructor
public class SearchUsageListener {
    private final SearchBillingService searchBillingService;

    @RabbitListener(queues = SearchRabbitConfig.SEARCH_QUEUE, containerFactory = "searchListenerContainerFactory")
    public void onSearchUsage(SearchUsageEvent event) {
        searchBillingService.chargeLocal(event);
    }
}
