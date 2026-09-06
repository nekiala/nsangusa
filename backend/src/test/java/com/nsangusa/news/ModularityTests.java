package com.nsangusa.news;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {
  @Test
  void modulesRespectDeclaredBoundaries() {
    ApplicationModules.of(NewsPlatformApplication.class).verify();
  }
}
