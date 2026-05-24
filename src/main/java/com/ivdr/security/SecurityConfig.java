package com.ivdr.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Central Spring Security configuration for the IVDR application.
 *
 * <p>Key design decisions:
 * <ul>
 *   <li>Uses the Spring Security 6.x lambda DSL; {@code WebSecurityConfigurerAdapter} is gone.</li>
 *   <li>Stateless JWT authentication — no HTTP session is created or used.</li>
 *   <li>CSRF protection is disabled because all state mutations are guarded by JWT.</li>
 *   <li>CORS origins are externalised to {@code app.security.allowed-origins}.</li>
 *   <li>Method-level security ({@code @PreAuthorize}, {@code @PostAuthorize}) is enabled.</li>
 *   <li>An async task executor backed by Java 21 Virtual Threads is registered so that
 *       Spring {@code @Async} tasks and security-context propagation benefit from
 *       lightweight threading.</li>
 * </ul>
 *
 * <p>Required application property:
 * <pre>
 * app:
 *   security:
 *     allowed-origins: "http://localhost:3000,https://app.ivdr.example.com"
 * </pre>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true)
public class SecurityConfig {

    // -------------------------------------------------------------------------
    // Public endpoints — no authentication required
    // -------------------------------------------------------------------------

    private static final String[] PUBLIC_POST_PATHS = {
            "/auth/register",
            "/auth/login",
            "/auth/refresh"
    };

    private static final String[] PUBLIC_GET_PATHS = {
            "/actuator/health",
            "/",
            "/index.html",
            "/favicon.ico",
            "/css/**",
            "/js/**"
    };

    private static final String[] PUBLIC_WILDCARD_PATHS = {
            "/swagger-ui/**",
            "/api-docs/**",
            "/ws/**"
    };

    // -------------------------------------------------------------------------
    // Injected collaborators
    // -------------------------------------------------------------------------

    /** Comma-separated list of allowed CORS origins, e.g. "http://localhost:3000". */
    @Value("${app.security.allowed-origins}")
    private String allowedOrigins;

    private final JwtAuthFilter       jwtAuthFilter;
    private final UserDetailsService  userDetailsService;

    /**
     * @param jwtAuthFilter      the JWT bearer-token filter (must run before password auth)
     * @param userDetailsService service that loads {@link UserPrincipal} by username (email)
     */
    public SecurityConfig(JwtAuthFilter jwtAuthFilter,
                          UserDetailsService userDetailsService) {
        this.jwtAuthFilter      = jwtAuthFilter;
        this.userDetailsService = userDetailsService;
    }

    // -------------------------------------------------------------------------
    // SecurityFilterChain
    // -------------------------------------------------------------------------

    /**
     * Defines the HTTP security filter chain that governs every incoming request.
     *
     * @param http the {@link HttpSecurity} builder provided by Spring
     * @return the fully configured {@link SecurityFilterChain}
     * @throws Exception if filter chain configuration fails
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // ----------------------------------------------------------------
            // Disable CSRF — safe for stateless REST APIs authenticated via JWT
            // ----------------------------------------------------------------
            .csrf(AbstractHttpConfigurer::disable)

            // ----------------------------------------------------------------
            // CORS — origins configured externally via app.security.allowed-origins
            // ----------------------------------------------------------------
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))

            // ----------------------------------------------------------------
            // Session management — STATELESS: no HttpSession is created
            // ----------------------------------------------------------------
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // ----------------------------------------------------------------
            // Authorization rules
            // ----------------------------------------------------------------
            .authorizeHttpRequests(auth -> auth
                    // Public auth endpoints
                    .requestMatchers(HttpMethod.POST, PUBLIC_POST_PATHS).permitAll()
                    // Public read-only endpoints
                    .requestMatchers(HttpMethod.GET,  PUBLIC_GET_PATHS).permitAll()
                    // Swagger / OpenAPI UI — must be accessible without auth
                    .requestMatchers(PUBLIC_WILDCARD_PATHS).permitAll()
                    // Everything else must be authenticated
                    .anyRequest().authenticated()
            )

            // ----------------------------------------------------------------
            // Authentication provider — delegates to UserDetailsService + BCrypt
            // ----------------------------------------------------------------
            .authenticationProvider(authenticationProvider())

            // ----------------------------------------------------------------
            // Insert the JWT filter before Spring's password auth filter
            // ----------------------------------------------------------------
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // -------------------------------------------------------------------------
    // CORS
    // -------------------------------------------------------------------------

    /**
     * Builds a {@link CorsConfigurationSource} from the externally configured origin list.
     *
     * <p>The configuration permits the standard HTTP methods used by REST clients and
     * allows the {@code Authorization} header (needed for Bearer tokens) plus
     * {@code Content-Type}.
     *
     * @return source applied to all paths ("/**")
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        // Split the comma-delimited property value; trim whitespace around each entry
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
        config.setAllowedOrigins(origins);

        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Requested-With"));
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);

        // Pre-flight cache for 1 hour
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    // -------------------------------------------------------------------------
    // Authentication beans
    // -------------------------------------------------------------------------

    /**
     * {@link DaoAuthenticationProvider} wired with our custom {@link UserDetailsService}
     * and BCrypt password encoder.  Used during username/password login flows.
     *
     * @return configured authentication provider
     */
    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    /**
     * Exposes the {@link AuthenticationManager} so that the Auth service can
     * programmatically authenticate credentials during login.
     *
     * @param config Spring's {@link AuthenticationConfiguration}
     * @return the application-wide {@link AuthenticationManager}
     * @throws Exception if the manager cannot be retrieved
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * BCrypt password encoder with default strength (10 rounds).
     * Injected into {@link DaoAuthenticationProvider} and available for direct
     * use in the user-registration flow.
     *
     * @return {@link BCryptPasswordEncoder} instance
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // -------------------------------------------------------------------------
    // Virtual Threads executor  (Java 21)
    // -------------------------------------------------------------------------

    /**
     * An {@link Executor} backed by Java 21 Virtual Threads, registered as the
     * Spring async task executor.  Spring's {@code @Async} infrastructure and
     * the security context are propagated automatically when this executor is used
     * with Spring Security's context-aware task decorator.
     *
     * <p>Virtual threads are extremely lightweight (< 1 KB of memory per thread vs.
     * ~1 MB for platform threads), making them ideal for I/O-bound async tasks.
     *
     * @return virtual-thread-per-task executor
     */
    @Bean(name = "virtualThreadExecutor")
    public Executor virtualThreadExecutor() {
        // Thread.ofVirtual().name(..., 0) creates threads named "ivdr-vt-0", "ivdr-vt-1", …
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual()
                      .name("ivdr-vt-", 0)
                      .factory()
        );
    }
}
