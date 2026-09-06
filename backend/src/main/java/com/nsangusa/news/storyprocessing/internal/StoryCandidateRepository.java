package com.nsangusa.news.storyprocessing.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface StoryCandidateRepository extends JpaRepository<StoryCandidate, UUID> {}
