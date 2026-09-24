package com.nsangusa.news.storyprocessing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nsangusa.news.audit.AuditService;
import com.nsangusa.news.integration.NewsEvents.XPostNormalized;
import com.nsangusa.news.sourceingestion.SourceIngestionService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class StoryWorkspaceTests {
  private final StoryCandidateRepository stories = mock(StoryCandidateRepository.class);
  private final StoryCandidateSourceRepository memberships =
      mock(StoryCandidateSourceRepository.class);
  private final SourceIngestionService sources = mock(SourceIngestionService.class);
  private final AuditService audit = mock(AuditService.class);
  private final StoryApplicationService service =
      new StoryApplicationService(stories, memberships, sources, audit);

  @Test
  void regenerationCreatesANewCandidateWithoutChangingThePublishedDraft() {
    var original = candidate();
    original.status = "drafted";
    original.draftArticleId = UUID.randomUUID();
    UUID priorArticle = original.draftArticleId;
    when(stories.findLockedById(original.id)).thenReturn(Optional.of(original));
    var membership = membership(original.id, original.primarySourcePostId);
    when(memberships.findByStoryCandidateIdOrderByPublishedAtAsc(original.id))
        .thenReturn(List.of(membership));
    var current = mock(SourceIngestionService.SourceView.class);
    when(current.id()).thenReturn(original.primarySourcePostId);
    when(sources.getSource(original.primarySourcePostId)).thenReturn(current);

    UUID replacement = service.regenerate(original.id, UUID.randomUUID());

    var saved = ArgumentCaptor.forClass(StoryCandidate.class);
    verify(stories).saveAndFlush(saved.capture());
    assertThat(saved.getValue().id).isEqualTo(replacement).isNotEqualTo(original.id);
    assertThat(saved.getValue().regeneratedFromId).isEqualTo(original.id);
    assertThat(saved.getValue().status).isEqualTo("collecting");
    assertThat(original.draftArticleId).isEqualTo(priorArticle);
    assertThat(original.status).isEqualTo("drafted");
    verify(sources).assertSourcesPublishable(List.of(original.primarySourcePostId));
  }

  @Test
  void inFlightCandidatesCannotBeRegenerated() {
    var candidate = candidate();
    when(stories.findLockedById(candidate.id)).thenReturn(Optional.of(candidate));
    assertThatThrownBy(() -> service.regenerate(candidate.id, UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(sources, audit, memberships);
  }

  @Test
  void listsBoundedQueuesWithSourceAndArticleReferences() {
    var candidate = candidate();
    when(stories.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(candidate)));
    when(memberships.findByStoryCandidateIdOrderByPublishedAtAsc(candidate.id))
        .thenReturn(List.of(membership(candidate.id, candidate.primarySourcePostId)));

    var result = service.list(0, 20);
    assertThat(result.items())
        .singleElement()
        .satisfies(
            view -> assertThat(view.sourceIds()).containsExactly(candidate.primarySourcePostId));
    assertThatThrownBy(() -> service.list(0, 101)).isInstanceOf(IllegalArgumentException.class);
  }

  private StoryCandidate candidate() {
    return new StoryCandidate(UUID.randomUUID(), UUID.randomUUID(), "culture");
  }

  private StoryCandidateSource membership(UUID storyId, UUID sourceId) {
    return new StoryCandidateSource(
        storyId,
        new XPostNormalized(
            sourceId,
            "123",
            "Library",
            "https://x.com/Library/status/123",
            "A reported library update",
            Instant.now(),
            Set.of("culture")));
  }
}
