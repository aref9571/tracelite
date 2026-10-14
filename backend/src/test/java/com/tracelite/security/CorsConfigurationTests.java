package com.tracelite.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tracelite.config.IngestionConfiguration;
import com.tracelite.issue.api.IssueController;
import com.tracelite.issue.application.IssueLifecycleService;
import com.tracelite.issue.application.IssueQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = IssueController.class, properties = "tracelite.security.allowed-origins=http://localhost:5173")
@Import({IngestionConfiguration.class, SecurityConfiguration.class})
class CorsConfigurationTests {

    private static final String ALLOWED_ORIGIN = "http://localhost:5173";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IssueQueryService issueQueryService;

    @MockitoBean
    private IssueLifecycleService issueLifecycleService;

    @Test
    void answersAPreflightRequestFromAConfiguredOriginWithoutAnApiKey() throws Exception {
        mockMvc.perform(options("/api/v1/issues")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", TestApiKeys.HEADER))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Headers", TestApiKeys.HEADER))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    @Test
    void rejectsAPreflightRequestFromAnotherOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/issues")
                        .header("Origin", "https://attacker.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void rejectsAPreflightRequestForAMethodThatIsNotAllowed() throws Exception {
        mockMvc.perform(options("/api/v1/issues")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "DELETE"))
                .andExpect(status().isForbidden());
    }

    @Test
    void stillRequiresAnApiKeyForCrossOriginRequests() throws Exception {
        mockMvc.perform(get("/api/v1/issues").header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN));
    }
}
