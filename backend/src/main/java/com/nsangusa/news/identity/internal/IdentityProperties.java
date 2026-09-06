package com.nsangusa.news.identity.internal;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("news.identity")
class IdentityProperties {
  private Duration verificationTokenTtl = Duration.ofMinutes(30);
  private Duration passwordResetTokenTtl = Duration.ofMinutes(15);
  private Duration consumedTokenRetention = Duration.ofHours(24);
  private int maximumFailedAttempts = 5;
  private Duration failedAttemptWindow = Duration.ofMinutes(15);
  private Duration lockDuration = Duration.ofMinutes(30);
  private int maximumSessions = 5;
  private String oidcSuccessUrl = "/";
  private final Oidc oidc = new Oidc();

  public Duration getVerificationTokenTtl() {
    return verificationTokenTtl;
  }

  public void setVerificationTokenTtl(Duration verificationTokenTtl) {
    this.verificationTokenTtl = verificationTokenTtl;
  }

  public Duration getPasswordResetTokenTtl() {
    return passwordResetTokenTtl;
  }

  public void setPasswordResetTokenTtl(Duration passwordResetTokenTtl) {
    this.passwordResetTokenTtl = passwordResetTokenTtl;
  }

  public Duration getConsumedTokenRetention() {
    return consumedTokenRetention;
  }

  public void setConsumedTokenRetention(Duration consumedTokenRetention) {
    this.consumedTokenRetention = consumedTokenRetention;
  }

  public int getMaximumFailedAttempts() {
    return maximumFailedAttempts;
  }

  public void setMaximumFailedAttempts(int maximumFailedAttempts) {
    this.maximumFailedAttempts = maximumFailedAttempts;
  }

  public Duration getFailedAttemptWindow() {
    return failedAttemptWindow;
  }

  public void setFailedAttemptWindow(Duration failedAttemptWindow) {
    this.failedAttemptWindow = failedAttemptWindow;
  }

  public Duration getLockDuration() {
    return lockDuration;
  }

  public void setLockDuration(Duration lockDuration) {
    this.lockDuration = lockDuration;
  }

  public int getMaximumSessions() {
    return maximumSessions;
  }

  public void setMaximumSessions(int maximumSessions) {
    this.maximumSessions = maximumSessions;
  }

  public String getOidcSuccessUrl() {
    return oidcSuccessUrl;
  }

  public void setOidcSuccessUrl(String oidcSuccessUrl) {
    this.oidcSuccessUrl = oidcSuccessUrl;
  }

  public Oidc getOidc() {
    return oidc;
  }

  static class Oidc {
    private boolean requireVerifiedEmail = true;
    private String rolesClaim = "roles";
    private Map<String, String> roleMapping = new LinkedHashMap<>();

    public boolean isRequireVerifiedEmail() {
      return requireVerifiedEmail;
    }

    public void setRequireVerifiedEmail(boolean requireVerifiedEmail) {
      this.requireVerifiedEmail = requireVerifiedEmail;
    }

    public String getRolesClaim() {
      return rolesClaim;
    }

    public void setRolesClaim(String rolesClaim) {
      this.rolesClaim = rolesClaim;
    }

    public Map<String, String> getRoleMapping() {
      return roleMapping;
    }

    public void setRoleMapping(Map<String, String> roleMapping) {
      this.roleMapping = roleMapping;
    }
  }
}
