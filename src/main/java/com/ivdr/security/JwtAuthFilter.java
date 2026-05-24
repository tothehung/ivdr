package com.ivdr.security;

import com.ivdr.domain.auth.service.TenantContextService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Servlet filter that intercepts every request exactly once and performs JWT-based
 * stateless authentication.
 *
 * <p>Processing pipeline for each request:
 * <ol>
 *   <li>Extract the bearer token from the {@code Authorization} header.</li>
 *   <li>Validate the token via {@link JwtTokenProvider}.</li>
 *   <li>Build a {@link UserPrincipal} directly from JWT claims — <strong>no database call</strong>.</li>
 *   <li>Store a fully-authenticated {@link UsernamePasswordAuthenticationToken} in the
 *       {@link SecurityContextHolder}.</li>
 *   <li>Notify {@link TenantContextService} of the current tenant so downstream
 *       components can scope data access correctly.</li>
 *   <li>Continue the filter chain.</li>
 * </ol>
 *
 * <p>If the token is absent or invalid the filter simply continues the chain without
 * populating the security context; Spring Security will then reject protected
 * endpoints with HTTP 401.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    /** HTTP header carrying the token. */
    private static final String AUTHORIZATION_HEADER = "Authorization";

    /** Required prefix; everything after the space is the raw JWT compact string. */
    private static final String BEARER_PREFIX = "Bearer ";

    // Custom claim keys — must match those used in JwtTokenProvider
    private static final String CLAIM_ORG_ID  = "orgId";
    private static final String CLAIM_EMAIL   = "email";
    private static final String CLAIM_ROLE    = "role";

    private final JwtTokenProvider    jwtTokenProvider;
    private final TenantContextService tenantContextService;

    /**
     * Constructor injection is preferred over field injection for testability.
     *
     * @param jwtTokenProvider    validates tokens and extracts claims
     * @param tenantContextService propagates the tenant identifier for the current thread
     */
    public JwtAuthFilter(JwtTokenProvider jwtTokenProvider,
                         TenantContextService tenantContextService) {
        this.jwtTokenProvider     = jwtTokenProvider;
        this.tenantContextService = tenantContextService;
    }

    // -------------------------------------------------------------------------
    // OncePerRequestFilter
    // -------------------------------------------------------------------------

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest  request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain         filterChain
    ) throws ServletException, IOException {

        try {
            String token = extractBearerToken(request);

            if (StringUtils.hasText(token) && jwtTokenProvider.validateToken(token)) {
                authenticateRequest(token, request);
            }
        } catch (Exception ex) {
            // Never block the chain; Spring Security enforces authorization after the filter.
            log.warn("Could not set user authentication in security context: {}", ex.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Parses the JWT claims, reconstructs a {@link UserPrincipal} from them, and
     * stores the resulting {@link UsernamePasswordAuthenticationToken} in the
     * {@link SecurityContextHolder}.
     *
     * <p>Also propagates the tenant identifier to {@link TenantContextService} so
     * that downstream repository/service beans can apply row-level security or
     * schema-switching as required.
     *
     * @param token   validated JWT compact string
     * @param request current HTTP request (used to build authentication details)
     */
    private void authenticateRequest(String token, HttpServletRequest request) {
        Claims claims = jwtTokenProvider.getClaimsFromToken(token);

        UUID   userId   = UUID.fromString(claims.getSubject());
        UUID   orgId    = UUID.fromString(claims.get(CLAIM_ORG_ID, String.class));
        String email    = claims.get(CLAIM_EMAIL, String.class);
        String role     = claims.get(CLAIM_ROLE,  String.class);

        // Reconstruct principal from token claims — no DB round-trip required.
        // Fields that aren't stored in the JWT (fullName, passwordHash) are left blank
        // because they are not needed for authorization decisions.
        UserPrincipal principal = new UserPrincipal(
                userId,
                orgId,
                email,
                /* fullName     */ "",
                role,
                /* passwordHash */ ""
        );

        List<SimpleGrantedAuthority> authorities =
                List.of(new SimpleGrantedAuthority("ROLE_" + role));

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, authorities);

        // Attach request metadata (remote IP, session ID) to the authentication object.
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        // Set the tenant context BEFORE populating the SecurityContext so that any
        // security expression evaluation that triggers a DB lookup already has the
        // correct tenant in scope.
        tenantContextService.setTenant(orgId);

        SecurityContextHolder.getContext().setAuthentication(authentication);

        log.debug("Authenticated user '{}' (org={}) for request {} {}",
                email, orgId, request.getMethod(), request.getRequestURI());
    }

    /**
     * Extracts the raw JWT string from the {@code Authorization: Bearer <token>} header.
     *
     * @param request incoming HTTP request
     * @return the token string, or {@code null} if the header is absent or malformed
     */
    private String extractBearerToken(HttpServletRequest request) {
        String headerValue = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(headerValue) && headerValue.startsWith(BEARER_PREFIX)) {
            return headerValue.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
