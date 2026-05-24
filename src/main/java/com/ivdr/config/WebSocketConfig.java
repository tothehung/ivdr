package com.ivdr.config;

import com.ivdr.security.JwtTokenProvider;
import com.ivdr.security.UserPrincipal;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.UUID;

/**
 * WebSocket STOMP configuration.
 *
 * <p>Protocol: STOMP over WebSocket (SockJS fallback for older browsers)
 *
 * <p>Message flow:
 * <ul>
 *   <li>Client connects to {@code /ws}</li>
 *   <li>Client subscribes to {@code /topic/presence/{workspaceId}} for live presence</li>
 *   <li>Client subscribes to {@code /user/queue/alerts} for personal anomaly alerts</li>
 *   <li>Server sends via {@code /topic/*} (broadcast) or {@code /user/*} (targeted)</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final String allowedOrigins;
    private final JwtTokenProvider jwtTokenProvider;

    public WebSocketConfig(
            @Value("${spring.websocket.allowed-origins:*}") String allowedOrigins,
            JwtTokenProvider jwtTokenProvider) {
        this.allowedOrigins = allowedOrigins;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Enable simple in-memory broker for /topic and /queue destinations
        registry.enableSimpleBroker("/topic", "/queue");
        // Application destination prefix for messages sent by clients
        registry.setApplicationDestinationPrefixes("/app");
        // Prefix for user-specific messages
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(allowedOrigins)
                .withSockJS()               // SockJS fallback for browsers that don't support WebSocket
                    .setHeartbeatTime(25_000);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor =
                        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

                if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                    String authHeader = accessor.getFirstNativeHeader("Authorization");
                    if (authHeader != null && authHeader.startsWith("Bearer ")) {
                        String token = authHeader.substring(7);
                        try {
                            if (jwtTokenProvider.validateToken(token)) {
                                Claims claims = jwtTokenProvider.getClaimsFromToken(token);
                                UUID userId = UUID.fromString(claims.getSubject());
                                UUID orgId = UUID.fromString(claims.get("orgId", String.class));
                                String email = claims.get("email", String.class);
                                String role = claims.get("role", String.class);

                                UserPrincipal principal = new UserPrincipal(
                                        userId, orgId, email, "", role, ""
                                );
                                UsernamePasswordAuthenticationToken auth =
                                        new UsernamePasswordAuthenticationToken(
                                                principal, null, principal.getAuthorities()
                                        );
                                accessor.setUser(auth);

                                // Put user identity in WebSocket session attributes so it is
                                // accessible via headerAccessor.getSessionAttributes()
                                if (accessor.getSessionAttributes() != null) {
                                    accessor.getSessionAttributes().put("userId", userId);
                                    accessor.getSessionAttributes().put("orgId", orgId);
                                }

                                log.debug("[WS Authed] User={} connected to WebSocket", userId);
                            } else {
                                log.warn("[WS Auth Failed] Invalid token signature/expiry");
                            }
                        } catch (Exception ex) {
                            log.error("[WS Auth Error] Failed parsing WebSocket credentials: {}", ex.getMessage());
                        }
                    } else {
                        log.warn("[WS Auth Missing] No Authorization Bearer header found in STOMP connect");
                    }
                }
                return message;
            }
        });
    }
}
