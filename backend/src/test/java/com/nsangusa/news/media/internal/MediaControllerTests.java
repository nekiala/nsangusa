package com.nsangusa.news.media.internal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nsangusa.news.media.MediaService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MediaControllerTests {
  @Test
  void generationContentUsesDatabaseIdAndDisablesBrowserCaching() throws Exception {
    var media = mock(MediaService.class);
    UUID generationId = UUID.randomUUID();
    when(media.generationImage(generationId, "hero"))
        .thenReturn(new MediaService.ImageContent(new byte[] {1, 2, 3}, "image/png"));
    var mvc =
        MockMvcBuilders.standaloneSetup(
                new MediaController(
                    media,
                    org.mockito.Mockito.mock(
                        com.nsangusa.news.eventprocessing.DurableCommandExecutor.class)))
            .build();

    mvc.perform(get("/api/v1/admin/image-generations/{id}/content", generationId))
        .andExpect(status().isOk())
        .andExpect(content().contentType("image/png"))
        .andExpect(content().bytes(new byte[] {1, 2, 3}))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"));
  }
}
