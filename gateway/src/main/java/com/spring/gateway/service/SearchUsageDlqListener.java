package com.spring.gateway.service;

import com.spring.gateway.config.SearchRabbitConfig;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 搜索计费死信持久化。
 *
 * @author hanbing
 * @since 2026-10-18
 */
@Component
@RequiredArgsConstructor
public class SearchUsageDlqListener {
    private static final Logger log = LoggerFactory.getLogger(SearchUsageDlqListener.class);
    /** 数据库失败后的重投间隔（毫秒）。 */
    private static final long REQUEUE_DELAY_MS = 5000;
    private final SearchUsageListener listener;

    @RabbitListener(queues = SearchRabbitConfig.SEARCH_DLQ, containerFactory = "searchDlqListenerContainerFactory")
    public void onDeadLetter(Message message) {
        try {
            listener.onSearchUsage(message);
        } catch (Exception exception) {
            log.error("搜索死信保存失败，保留消息", exception);
            pauseBeforeRequeue();
            throw new ImmediateRequeueAmqpException("搜索死信尚未持久化", exception);
        }
    }

    void pauseBeforeRequeue() {
        try {
            Thread.sleep(REQUEUE_DELAY_MS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
