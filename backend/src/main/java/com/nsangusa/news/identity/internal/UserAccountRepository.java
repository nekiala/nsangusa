package com.nsangusa.news.identity.internal;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface UserAccountRepository
    extends JpaRepository<UserAccount, UUID>, JpaSpecificationExecutor<UserAccount> {
  Optional<UserAccount> findByEmailIgnoreCase(String email);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select user from UserAccount user where lower(user.email) = lower(:email)")
  Optional<UserAccount> findForAuthentication(@Param("email") String email);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select user from UserAccount user where user.id = :id")
  Optional<UserAccount> findForAdministration(@Param("id") UUID id);

  @Query(
      "select count(user) from UserAccount user where user.enabled = true"
          + " and user.emailVerified = true and user.deletedAt is null"
          + " and :role member of user.roles")
  long countActiveWithRole(@Param("role") UserAccount.Role role);
}
