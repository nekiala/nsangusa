package com.nsangusa.news.identity.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisIndexedHttpSession;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;

@Configuration(proxyBeanMethods = false)
@EnableRedisIndexedHttpSession(redisNamespace = "${spring.session.redis.namespace:news:session}")
class IdentitySessionConfiguration {
  @Bean
  SessionRegistry identitySessionRegistry(
      FindByIndexNameSessionRepository<? extends Session> sessions) {
    return new SpringSessionBackedSessionRegistry<>(sessions);
  }

  @Bean
  org.springframework.session.config.SessionRepositoryCustomizer<
          org.springframework.session.data.redis.RedisIndexedSessionRepository>
      identitySessionTimeout(org.springframework.core.env.Environment environment) {
    return repository ->
        repository.setDefaultMaxInactiveInterval(
            org.springframework.boot.convert.DurationStyle.detectAndParse(
                environment.getProperty("spring.session.timeout", "30m")));
  }

  @Bean
  IdentitySessionValidityFilter identitySessionValidityFilter(UserAccountRepository users) {
    return new IdentitySessionValidityFilter(users);
  }

  @Bean
  org.springframework.boot.web.servlet.FilterRegistrationBean<IdentitySessionValidityFilter>
      identitySessionFilterRegistration(IdentitySessionValidityFilter filter) {
    var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }
}
