package com.spring.gateway.config;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;

/**
 * 搜索消息原始字节转换器。
 *
 * @author hanbing
 * @since 2026-10-18
 */
public class SearchRawMessageConverter implements MessageConverter {
    @Override
    public Object fromMessage(Message message) {
        return message.getBody();
    }

    @Override
    public Message toMessage(Object object, MessageProperties properties) {
        if (!(object instanceof byte[] body)) {
            throw new IllegalArgumentException("搜索消息必须是原始字节");
        }
        return new Message(body, properties);
    }
}
