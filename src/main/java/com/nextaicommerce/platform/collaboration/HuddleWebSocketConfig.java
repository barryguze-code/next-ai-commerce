package com.nextaicommerce.platform.collaboration;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

@Configuration
@EnableWebSocket
public class HuddleWebSocketConfig implements WebSocketConfigurer {
    private final HuddleWebSocketHandler handler;
    public HuddleWebSocketConfig(HuddleWebSocketHandler handler){this.handler=handler;}
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry){
        registry.addHandler(handler,"/ws/huddles").addInterceptors(new HttpSessionHandshakeInterceptor());
    }
}
