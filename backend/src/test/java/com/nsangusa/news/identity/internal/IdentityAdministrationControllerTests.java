package com.nsangusa.news.identity.internal;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.nsangusa.news.identity.IdentityService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {UserAdministrationController.class, AuthController.class})
@Import({SecurityConfiguration.class, SecurityConfigurationTests.TestBeans.class})
class IdentityAdministrationControllerTests {
  @Autowired MockMvc mvc;
  @MockitoBean UserAccountRepository users;
  @MockitoBean IdentityOidcUserService oidcUsers;
  @MockitoBean LoginSecurityService loginSecurity;
  @MockitoBean UserAdministrationService administration;
  @MockitoBean IdentityService identity;
  final UUID target = UUID.randomUUID();

  @Test
  void usersAreAdministratorOnlyIncludingModeratorsAndEditors() throws Exception {
    mvc.perform(get("/api/v1/admin/users")).andExpect(status().isUnauthorized());
    for (String role : List.of("READER", "MODERATOR", "EDITOR")) {
      mvc.perform(get("/api/v1/admin/users").with(user("operator").roles(role)))
          .andExpect(status().isForbidden());
      mvc.perform(
              put("/api/v1/admin/users/" + target + "/roles")
                  .with(user("operator").roles(role))
                  .with(csrf())
                  .header("Idempotency-Key", "role-request")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(validRequest()))
          .andExpect(status().isForbidden());
    }
    verifyNoInteractions(administration);
  }

  @Test
  void listingPassesBoundedFiltersAndDisablesCaching() throws Exception {
    when(administration.list(
            "admin@example.test",
            "Reader",
            UserAccount.Role.READER,
            UserAdministrationService.Status.active,
            1,
            10))
        .thenReturn(new UserAdministrationService.UserPage(List.of(), 1, 10, 0));
    mvc.perform(
            get("/api/v1/admin/users?q=Reader&role=READER&status=active&page=1&size=10")
                .with(user("admin@example.test").roles("ADMINISTRATOR")))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.page").value(1));
    mvc.perform(get("/api/v1/admin/users?size=101").with(user("admin").roles("ADMINISTRATOR")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void roleMutationRequiresCsrfKeyVersionAndAllowlistedRolesAndDerivesActorFromPrincipal()
      throws Exception {
    var request =
        put("/api/v1/admin/users/" + target + "/roles")
            .with(user("admin@example.test").roles("ADMINISTRATOR"));
    mvc.perform(
            request
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequest())
                .header("Idempotency-Key", "role-request"))
        .andExpect(status().isForbidden());
    mvc.perform(
            put("/api/v1/admin/users/" + target + "/roles")
                .with(user("admin").roles("ADMINISTRATOR"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequest()))
        .andExpect(status().isBadRequest());
    for (String body :
        List.of(
            "{\"roles\":[\"ROOT\"],\"expectedVersion\":0,\"confirmation\":\"reader@example.test\"}",
            "{\"roles\":[],\"expectedVersion\":0,\"confirmation\":\"reader@example.test\"}",
            "{\"roles\":[\"READER\"],\"confirmation\":\"reader@example.test\"}")) {
      mvc.perform(
              put("/api/v1/admin/users/" + target + "/roles")
                  .with(user("admin").roles("ADMINISTRATOR"))
                  .with(csrf())
                  .header("Idempotency-Key", "role-request")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(
            put("/api/v1/admin/users/" + target + "/roles")
                .with(user("admin@example.test").roles("ADMINISTRATOR"))
                .with(csrf())
                .header("Idempotency-Key", "role-request")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequest()))
        .andExpect(status().isNoContent());
    verify(administration)
        .changeRoles(eq("admin@example.test"), eq(target), any(), eq("role-request"));
  }

  @Test
  void tokenLandingIsNeverAGetMutationAndAnonymousPostRequiresCsrf() throws Exception {
    mvc.perform(get("/api/v1/auth/verify-email?token=anything"))
        .andExpect(status().isMethodNotAllowed());
    mvc.perform(
            post("/api/v1/auth/verify-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"token\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/auth/verify-email")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"token\"}"))
        .andExpect(status().isNoContent());
    verify(identity).verifyEmail("token");
  }

  @Test
  void profileAndDeletionRequireVersionsAndRegistrationDoesNotExposeAccountExistence()
      throws Exception {
    mvc.perform(
            patch("/api/v1/auth/me")
                .with(user("reader"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Reader\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            delete("/api/v1/auth/me")
                .with(user("reader"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmation\":\"DELETE\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/auth/register")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"reader@example.test\",\"displayName\":\"Reader\",\"password\":\"long-demo-password\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("verification_required"))
        .andExpect(jsonPath("$.id").doesNotExist());
  }

  @Test
  void logoutReturnsNoContentForBrowserTransport() throws Exception {
    mvc.perform(post("/api/v1/auth/logout").with(user("reader")).with(csrf()))
        .andExpect(status().isNoContent());
  }

  private String validRequest() {
    return "{\"roles\":[\"READER\",\"EDITOR\"],\"expectedVersion\":0,\"confirmation\":\"reader@example.test\"}";
  }
}
