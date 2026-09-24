package com.nsangusa.news.aieditorial.internal;

import com.nsangusa.news.aieditorial.EditorialRequestService;
import com.nsangusa.news.aieditorial.EditorialRequestService.AiRequestView;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/ai-requests")
@PreAuthorize("hasAnyRole('EDITOR', 'ADMINISTRATOR')")
class AiRequestController {
  private final EditorialRequestService requests;

  AiRequestController(EditorialRequestService requests) {
    this.requests = requests;
  }

  @GetMapping
  List<AiRequestView> list(@RequestParam UUID storyCandidateId) {
    return requests.list(storyCandidateId);
  }
}
