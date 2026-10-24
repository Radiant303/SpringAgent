package com.spring.gateway.search.infrastructure;

import com.spring.gateway.config.SearchRabbitConfig;
import com.spring.gateway.search.dto.SearchUsageEvent;
import com.spring.gateway.search.service.SearchBillingFailureService;
import com.spring.gateway.search.service.SearchBillingService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 搜索用量事件监听。
 *
 * @author hanbing
 * @since 2026-10-17
 */
@Component
@RequiredArgsConstructor
public class SearchUsageListener {
    private static final Logger log = LoggerFactory.getLogger(SearchUsageListener.class);
    private final SearchBillingService searchBillingService;
    private final SearchBillingFailureService failures;
    private final JsonMapper json;

    @RabbitListener(queues = SearchRabbitConfig.SEARCH_QUEUE, containerFactory = "searchListenerContainerFactory")
    public void onSearchUsage(Message message) {
        try {
            if (failures.recorded(message)) {
                return;
            }
            searchBillingService.chargeLocal(json.readValue(message.getBody(), SearchUsageEvent.class));
        } catch (Exception exception) {
            log.error("搜索计费失败", exception);
            failures.record(message, exception);
        }
    }
}
