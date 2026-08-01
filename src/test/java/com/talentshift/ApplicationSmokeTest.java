package com.talentshift;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties={"app.jobs.collection-on-startup=false","app.storage.upload-dir=target/test-uploads",
        "app.security.demo-email=tester@example.com","app.security.demo-password=StrongTestPassword123!",
        "app.security.demo-user-enabled=true"})
class ApplicationSmokeTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mockMvc;

    @Test
    void applicationStartsAndExposesPublicJobCount() throws Exception {
        mockMvc.perform(get("/api/jobs/count")).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void staticFrontendIsServed() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk());
        mockMvc.perform(get("/login.html")).andExpect(status().isOk());
        mockMvc.perform(get("/register.html")).andExpect(status().isOk());
    }

    @Test
    void candidateRegistrationCreatesSessionAndRejectsDuplicates() throws Exception {
        MvcResult registration = mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"role":"CANDIDATE","fullName":"New Candidate","email":"new@example.com",
                                 "password":"StrongCandidate123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("New Candidate"))
                .andReturn();
        Cookie session = registration.getResponse().getCookie(AuthService.SESSION_COOKIE);
        mockMvc.perform(get("/api/auth/me").cookie(session)).andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json")
                        .content("""
                                {"role":"CANDIDATE","fullName":"Duplicate","email":"new@example.com",
                                 "password":"StrongCandidate123"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void demoLoginCreatesUsableOpaqueSession() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"email":"tester@example.com","password":"StrongTestPassword123!"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("tester@example.com"))
                .andReturn();

        Cookie session = login.getResponse().getCookie(AuthService.SESSION_COOKIE);
        mockMvc.perform(get("/api/auth/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Demo Candidate"));
        mockMvc.perform(put("/api/workspace/preferences").cookie(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"newMatchingJobs\":false,\"interviewReminders\":true,\"employerMessages\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.newMatchingJobs").value(false));
    }

    @Test
    void companyDirectoryIsPublicAndSeeded() throws Exception {
        mockMvc.perform(get("/api/companies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(24)))
                .andExpect(jsonPath("$.items[0].careersUrl").isNotEmpty());
    }

    @Test
    void manualAgentSearchRequiresAdministratorAccountAndReportsMissingTavilyConfiguration() throws Exception {
        mockMvc.perform(post("/api/admin/jobs/agent-search"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/jobs/agent-search").header("X-Admin-Key", "legacy-key"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/jobs/agent-search").with(user("candidate").roles("CANDIDATE")).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/integration/audit").with(user("candidate").roles("CANDIDATE")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/jobs/agent-search").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false));
        mockMvc.perform(get("/api/admin/job-sources/status").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.aiCreditLimit").value(200));
    }
}
