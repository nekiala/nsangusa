package com.nsangusa.news.identity.internal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(IdentityProperties.class)
class SecurityConfiguration {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new Argon2PasswordEncoder(16, 32, 1, 65_536, 3);
  }

  @Bean
  UserDetailsService userDetailsService(UserAccountRepository users) {
    return username -> {
      var user =
          users
              .findByEmailIgnoreCase(username)
              .orElseThrow(
                  () ->
                      new org.springframework.security.core.userdetails.UsernameNotFoundException(
                          "User not found"));
      var authorities =
          user.roles.stream()
              .map(role -> "ROLE_" + role.name())
              .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
              .toList();
      return new org.springframework.security.core.userdetails.User(
          user.email,
          user.passwordHash,
          user.enabled && user.emailVerified && user.localCredentialsEnabled,
          true,
          true,
          !user.isLocked(java.time.Instant.now()),
          authorities);
    };
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      RedisRateLimitFilter rateLimit,
      IdentityOidcUserService oidcUsers,
      LoginSecurityService loginSecurity,
      IdentityProperties properties,
      ObjectProvider<ClientRegistrationRepository> clientRegistrations)
      throws Exception {
    var csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
    csrf.setCookieName("XSRF-TOKEN");
    csrf.setCookiePath("/");
    http.addFilterBefore(
            rateLimit,
            org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
                .class)
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/articles/**",
                        "/api/v1/topics/**",
                        "/api/v1/tags/**",
                        "/api/v1/search")
                    .permitAll()
                    .requestMatchers(
                        "/api/v1/newsletter/**",
                        "/api/v1/auth/register",
                        "/api/v1/auth/csrf",
                        "/api/v1/auth/verify-email",
                        "/api/v1/auth/password-reset/**",
                        "/actuator/health/**",
                        "/api/openapi/**",
                        "/api/docs/**",
                        "/swagger-ui/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .csrf(csrfConfig -> csrfConfig.csrfTokenRepository(csrf))
        .httpBasic(basic -> {})
        .formLogin(
            form ->
                form.loginProcessingUrl("/api/v1/auth/login")
                    .successHandler(IdentityAuthenticationHandlers.apiSuccess())
                    .failureHandler(IdentityAuthenticationHandlers.apiFailure()))
        .logout(
            logout ->
                logout
                    .logoutUrl("/api/v1/auth/logout")
                    .invalidateHttpSession(true)
                    .deleteCookies("SESSION", "JSESSIONID"))
        .sessionManagement(
            sessions -> {
              sessions.sessionFixation(fixation -> fixation.changeSessionId());
              sessions
                  .maximumSessions(properties.getMaximumSessions())
                  .maxSessionsPreventsLogin(false);
            })
        .headers(
            headers ->
                headers
                    .contentSecurityPolicy(
                        csp ->
                            csp.policyDirectives(
                                "default-src 'self'; img-src 'self' data:; style-src 'self';"
                                    + " script-src 'self'; object-src 'none'; base-uri 'self';"
                                    + " frame-ancestors 'none'"))
                    .referrerPolicy(
                        referrer ->
                            referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy
                                    .STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                    .permissionsPolicyHeader(
                        permissions ->
                            permissions.policy(
                                "camera=(), microphone=(), geolocation=(), payment=()"))
                    .httpStrictTransportSecurity(
                        hsts ->
                            hsts.includeSubDomains(true)
                                .preload(true)
                                .maxAgeInSeconds(31_536_000)));
    if (clientRegistrations.getIfAvailable() != null) {
      http.oauth2Login(
          oauth ->
              oauth
                  .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUsers))
                  .successHandler(
                      IdentityAuthenticationHandlers.oidcSuccess(
                          loginSecurity, properties.getOidcSuccessUrl()))
                  .failureHandler(IdentityAuthenticationHandlers.apiFailure()));
    }
    return http.build();
  }
}
