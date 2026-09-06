package com.nsangusa.news.identity.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ExternalIdentityRepository extends JpaRepository<ExternalIdentity, UUID> {
  Optional<ExternalIdentity> findByIssuerAndSubject(String issuer, String subject);

  List<ExternalIdentity> findByUserIdOrderByCreatedAt(UUID userId);

  void deleteByUserId(UUID userId);
}
