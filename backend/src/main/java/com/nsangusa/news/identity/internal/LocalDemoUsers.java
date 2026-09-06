package com.nsangusa.news.identity.internal;

import java.util.Set;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile({"local", "seed"})
class LocalDemoUsers implements ApplicationRunner {
  private final UserAccountRepository users;
  private final PasswordEncoder passwords;

  LocalDemoUsers(UserAccountRepository users, PasswordEncoder passwords) {
    this.users = users;
    this.passwords = passwords;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    create("reader@example.test", "reader-demo-password", UserAccount.Role.READER);
    create("moderator@example.test", "moderator-demo-password", UserAccount.Role.MODERATOR);
    create("editor@example.test", "editor-demo-password", UserAccount.Role.EDITOR);
    create("admin@example.test", "administrator-demo-password", UserAccount.Role.ADMINISTRATOR);
  }

  private void create(String email, String password, UserAccount.Role role) {
    if (users.findByEmailIgnoreCase(email).isEmpty()) {
      users.save(
          new UserAccount(
              UUID.randomUUID(),
              email,
              role.name(),
              passwords.encode(password),
              true,
              Set.of(role)));
    }
  }
}
