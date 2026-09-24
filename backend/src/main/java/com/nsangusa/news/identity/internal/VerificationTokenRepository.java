package com.nsangusa.news.identity.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface VerificationTokenRepository extends JpaRepository<VerificationToken, UUID> {
  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  Optional<VerificationToken> findByTokenHash(String tokenHash);

  void deleteByUserIdAndPurpose(UUID userId, String purpose);

  void deleteByUserId(UUID userId);

  @Modifying
  @Query(
      "delete from VerificationToken token where token.expiresAt < :now or (token.usedAt is not"
          + " null and token.usedAt < :usedBefore)")
  int deleteExpiredAndConsumed(@Param("now") Instant now, @Param("usedBefore") Instant usedBefore);
}
