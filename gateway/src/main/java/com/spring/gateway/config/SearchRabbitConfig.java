package com.spring.gateway.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * 搜索计费消息拓扑。
 *
 * @author hanbing
 * @since 2026-10-17
 */
@Configuration
public class SearchRabbitConfig {
    public static final String SEARCH_EXCHANGE = "springagent.search";
    public static final String SEARCH_ROUTING_KEY = "search.usage";
    public static final String SEARCH_QUEUE = "springagent.search.usage";
    public static final String SEARCH_DLX = "springagent.search.dlx";
    public static final String SEARCH_DLQ = "springagent.search.usage.dlq";
    public static final String SEARCH_DL_ROUTING_KEY = "search.usage.failed";

    @Bean
    public DirectExchange searchExchange() {
        return new DirectExchange(SEARCH_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange searchDeadLetterExchange() {
        return new DirectExchange(SEARCH_DLX, true, false);
    }

    @Bean
    public Queue searchUsageQueue() {
        return QueueBuilder.durable(SEARCH_QUEUE)
                .withArgument("x-dead-letter-exchange", SEARCH_DLX)
                .withArgument("x-dead-letter-routing-key", SEARCH_DL_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue searchUsageDlq() {
        return QueueBuilder.durable(SEARCH_DLQ).build();
    }

    @Bean
    public Binding searchUsageBinding(DirectExchange searchExchange, Queue searchUsageQueue) {
        return BindingBuilder.bind(searchUsageQueue).to(searchExchange).with(SEARCH_ROUTING_KEY);
    }

    @Bean
    public Binding searchUsageDlqBinding(DirectExchange searchDeadLetterExchange, Queue searchUsageDlq) {
        return BindingBuilder.bind(searchUsageDlq).to(searchDeadLetterExchange).with(SEARCH_DL_ROUTING_KEY);
    }

    @Bean
    public SimpleRabbitListenerContainerFactory searchListenerContainerFactory(
            ConnectionFactory connectionFactory, JsonMapper objectMapper) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(new SearchRawMessageConverter());
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory searchDlqListenerContainerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(new SearchRawMessageConverter());
        factory.setDefaultRequeueRejected(true);
        factory.setPrefetchCount(1);
        factory.setConcurrentConsumers(1);
        return factory;
    }
}
