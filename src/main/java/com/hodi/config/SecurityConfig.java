package com.hodi.config;

import com.hodi.common.ApiResponse;
import com.hodi.security.BuyerVerificationRequiredFilter;
import com.hodi.security.PublicMarketplaceFilter;
import com.hodi.security.jwt.JwtAuthenticationFilter;
import com.hodi.security.password.PasswordChangeRequiredFilter;
import com.hodi.tenant.TenantBindingFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;

/**
 * Stateless JWT filter chain.
 *
 * <p>Public by design and nothing more: the bootstrap endpoints the client needs before anyone signs in, the
 * auth entry points, buyer self-registration, and the API docs. Everything else requires authentication, and
 * per-endpoint authority checks are done with {@code @PreAuthorize} at the controller — the permission
 * catalogue is too granular to express as URL patterns, and duplicating it here would give two places to keep
 * in step.
 *
 * <p>Errors return the same {@link ApiResponse} envelope as successes, so a client never has to parse two
 * shapes.
 *
 * <p>Note the Jackson 3 import: Spring Boot 4 auto-configures {@code tools.jackson.databind.ObjectMapper} and
 * no longer exposes a Jackson 2 bean. {@code PayloadSanitizer} and {@code RedisConfig} still build their own
 * Jackson 2 mappers because they need mutable {@code ObjectNode}s and a typed serializer respectively — the
 * two coexist deliberately.
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String[] PUBLIC_GET = {
            // The marketplace itself: listings and bank products a house-hunter reaches before they have
            // an account. A shop window behind authentication is not a shop window.
            "/api/v1/public/**",
            "/api/v1/auth/password-policy",
            "/actuator/health", "/actuator/health/**", "/actuator/info",
            "/api/v1/docs/**", "/api/v1/swagger-ui.html", "/api/v1/swagger-ui/**",
            // Locally-stored media. Public because a property photo is fetched by a browser with no session,
            // exactly as an S3 object would be, and the key it is addressed by is an unguessable UUID. This
            // path only serves anything when no bucket is configured; with S3 the browser never comes here.
            "/media/**",
    };

    private static final String[] PUBLIC_POST = {
            /*
             * Where Co-op tells us money arrived.
             *
             * The only unauthenticated write path here, and it cannot be otherwise: a payment gateway has no
             * session and never will. What stands in for authentication is a shared secret in a header — and
             * while that is unset the endpoint still accepts and stores every notification but credits
             * nothing automatically, because refusing them would make Co-op retry and eventually give up,
             * losing real money.
             */
            "/api/v1/public/coop/notifications",
            /*
             * The biller's two addresses: "is this reference real, and what is owed" and "the customer
             * paid it". Same caller, same reasoning; each is authenticated inside by the connection ID and
             * password on the biller's own account, and closed while those are unset.
             */
            "/api/v1/public/coop/biller/validation",
            "/api/v1/public/coop/biller/advice",
            // What became of a transfer we sent. HTTP Basic inside, like the notification.
            "/api/v1/public/coop/transfers/callback",
            // The one-click way out of promotional messages: a signed token in the body is what stands in
            // for a session, because the person is on a phone and has not signed in. It only ever refuses.
            "/api/v1/public/unsubscribe",
            "/api/v1/auth/login",
            // The second half of a challenged login: the caller has no session yet, only a one-shot
            // challenge token, so this cannot require authentication.
            "/api/v1/auth/login/verify-otp",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password",
            /*
             * Buyer self-registration and the verification that follows it.
             *
             * Public by necessity — the whole point is somebody who has no account yet. What protects them is
             * not a session: registration is rate-limited per address inside, the account is inert until a
             * code sent to a channel the registrant controls is confirmed, and the verification endpoints
             * count attempts against a durable challenge rather than letting a token be guessed at freely.
             */
            "/api/v1/public/buyers/register",
            "/api/v1/public/buyers/verify",
            "/api/v1/public/buyers/verify/resend",
            /*
             * The affordability calculator (M3).
             *
             * A POST under /api/v1/public is not public by default — the GET wildcard above covers reads and
             * writes are named one by one, which is the rule that stopped this from being open the moment it
             * was written. It is listed here because it is a POST only in shape: it writes nothing, calls
             * nothing external, holds no state and returns arithmetic over the numbers in the request. It
             * takes income figures and keeps none of them; the endpoint that keeps them is
             * /api/v1/me/affordability, and that one requires a session.
             */
            "/api/v1/public/affordability/estimate",
            /*
             * An agent applying to join the platform (M9, FR160).
             *
             * The most consequential thing on this list: it creates an account for somebody who intends to
             * list property, where buyer registration creates one for somebody who intends to look at it. It
             * is here rather than behind a session because an applicant by definition has neither.
             *
             * What makes it safe is that it grants nothing. The application lands PENDING, the profile's KYC
             * standing fails the listing gate, and no organisation exists to list into until the platform
             * approves it — three independent conditions, because one gate is one thing to get wrong. A
             * self-registered agent who is never approved can sign in and see an empty workspace, which is
             * exactly what they are.
             */
            "/api/v1/public/agents/apply",
            /*
             * A business applying to be listed in the vendor directory (M10, FR170).
             *
             * The same three conditions make it safe as the agent one: the application lands PENDING, the
             * profile's KYC standing fails the publication gate, and there is no organisation to publish a
             * catalogue into until the platform approves it.
             */
            "/api/v1/public/vendors/apply",
            /*
             * Somebody asking to sell property through the bank (seller onboarding §1).
             *
             * The same three conditions again, and here they are the whole design: no organisation exists
             * until the bank approves, the profile's KYC standing fails the listing gate, and the account
             * carries no group at all — so it resolves not one permission. What this endpoint creates is a
             * person who can sign in and finish their own application, which is the point of issuing
             * credentials before the decision rather than after it.
             */
            "/api/v1/public/sellers/apply",
    };

    private final PublicMarketplaceFilter publicMarketplaceFilter;
    private final JwtAuthenticationFilter jwtFilter;
    private final TenantBindingFilter tenantBindingFilter;
    private final PasswordChangeRequiredFilter passwordChangeFilter;
    private final BuyerVerificationRequiredFilter buyerVerificationFilter;
    private final ObjectMapper objectMapper;

    @Value("${hodi.cors.allowed-origins:http://localhost:3020}")
    private String allowedOrigins;

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                // No cookie-based session and no browser form posts to protect; the refresh cookies are
                // SameSite-scoped and the API is called with a bearer token.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET).permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_POST).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint())
                        .accessDeniedHandler(accessDeniedHandler()))
                /*
                 * Filter order is load-bearing, and each anchor is deliberate:
                 *
                 *  1. publicMarketplaceFilter — ahead of the JWT filter, so the marker is already set when a
                 *     signed-in buyer browses listings. On that surface they are a member of the public
                 *     whatever token they are carrying, and ids must salt accordingly.
                 *  2. jwtFilter — establishes the principal.
                 *  3. tenantBindingFilter — AFTER the JWT filter, because it binds the organisation off the
                 *     principal that filter created. Before it, there is nothing to read.
                 *  4. passwordChangeFilter — after the principal exists, so it can see that the holder of a
                 *     temporary or expired password may do nothing except change it.
                 *  5. buyerVerificationFilter — last, and after the password filter on purpose: a forced
                 *     password change outranks an outstanding verification, so whichever of the two applies
                 *     first should be the one the client is told about.
                 *
                 * All are anchored to UsernamePasswordAuthenticationFilter because Spring Security orders only
                 * against filters it knows, and none of ours is one of those.
                 */
                .addFilterBefore(publicMarketplaceFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(tenantBindingFilter, JwtAuthenticationFilter.class)
                .addFilterAfter(passwordChangeFilter, TenantBindingFilter.class)
                .addFilterAfter(buyerVerificationFilter, PasswordChangeRequiredFilter.class)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .build();
    }

    /**
     * 401, not a redirect — the client's axios interceptor treats 401 as "try one refresh, then send the user
     * to the login page", and an HTML redirect would break that contract.
     */
    private AuthenticationEntryPoint entryPoint() {
        return (request, response, authException) ->
                write(response, HttpStatus.UNAUTHORIZED, "Authentication required");
    }

    /** 403 for an authenticated caller lacking the permission — distinct from 401 on purpose. */
    private AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) ->
                write(response, HttpStatus.FORBIDDEN, "You do not have permission to do that");
    }

    private void write(jakarta.servlet.http.HttpServletResponse response, HttpStatus status, String message)
            throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(message));
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        /*
         * "*" has to go through setAllowedOriginPatterns, not setAllowedOrigins. Credentials are enabled
         * below for the refresh cookies, and the CORS spec forbids pairing them with a literal "*" in
         * Access-Control-Allow-Origin — Spring throws rather than emit a header no browser would honour.
         * Patterns match the request's origin and echo it back, which expresses the same intent
         * credential-safely.
         *
         * An explicit list still goes through setAllowedOrigins, so configuring real hosts keeps exact
         * matching rather than silently downgrading to pattern matching. Keep production narrow: with
         * credentials allowed, a wildcard lets any site the browser can reach issue credentialed requests
         * against this API — and here that includes a staff session.
         */
        if (origins.contains("*")) {
            config.setAllowedOriginPatterns(List.of("*"));
        } else {
            config.setAllowedOrigins(origins);
        }
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("X-Action-Id"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
