package com.nsangusa.news.aieditorial.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AiRequestRepository extends JpaRepository<AiRequestRecord, UUID> {}
