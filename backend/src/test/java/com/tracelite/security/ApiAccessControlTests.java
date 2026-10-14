package com.tracelite.security;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tracelite.config.IngestionConfiguration;
import com.tracelite.event.api.EventIngestionController;
import com.tracelite.event.api.IssueHistoryController;
import com.tracelite.event.application.EventIngestionService;
import com.tracelite.event.application.IssueHistoryService;
import com.tracelite.issue.api.IssueController;
import com.tracelite.issue.application.IssueLifecycleService;
import com.tracelite.issue.application.IssueQueryService;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest({EventIngestionController.class, IssueController.class, IssueHistoryController.class})
@Import({IngestionConfiguration.class, SecurityConfiguration.class})
class ApiAccessControlTests {

    private static final String ISSUE_PATH = "/api/v1/issues/5b1f7c9e-3f4a-4d2b-9a61-0c8e2f3d4a5b";
    private static final String EVENT_BODY = """
            {"application": "checkout-service", "environment": "production",
             "exceptionType": "java.lang.IllegalStateException", "occurredAt": "2026-10-12T08:59:30Z"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EventIngestionService eventIngestionService;

    @MockitoBean
    private IssueQueryService issueQueryService;

    @MockitoBean
    private IssueLifecycleService issueLifecycleService;

    @MockitoBean
    private IssueHistoryService issueHistoryService;

    @AfterEach
    void verifyNoHandlerWasCalled() {
        verifyNoInteractions(eventIngestionService, issueQueryService, issueLifecycleService, issueHistoryService);
    }

    static Stream<Arguments> protectedEndpoints() {
        return Stream.of(
                Arguments.of(HttpMethod.POST, "/api/v1/events"),
                Arguments.of(HttpMethod.GET, "/api/v1/issues"),
                Arguments.of(HttpMethod.GET, ISSUE_PATH),
                Arguments.of(HttpMethod.GET, ISSUE_PATH + "/events"),
                Arguments.of(HttpMethod.GET, ISSUE_PATH + "/stats"),
                Arguments.of(HttpMethod.POST, ISSUE_PATH + "/resolve"),
                Arguments.of(HttpMethod.POST, ISSUE_PATH + "/reopen"));
    }

    static Stream<Arguments> managementEndpoints() {
        return protectedEndpoints().filter(arguments -> !arguments.get()[1].equals("/api/v1/events"));
    }

    @ParameterizedTest
    @MethodSource("protectedEndpoints")
    void rejectsRequestsWithoutAnApiKey(HttpMethod method, String path) throws Exception {
        mockMvc.perform(apiRequest(method, path))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "ApiKey header=\"X-API-Key\""))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.detail").value("A valid API key is required in the X-API-Key header."))
                .andExpect(jsonPath("$.instance").value(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            " ",
            "short-key",
            "test management key 0123456789abcdef",
            "test-management-key-0123456789abcdef!",
            "test-management-key-0123456789abcdef\t"})
    void rejectsMalformedApiKeys(String malformedKey) throws Exception {
        mockMvc.perform(apiRequest(HttpMethod.GET, "/api/v1/issues").header(TestApiKeys.HEADER, malformedKey))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    void rejectsAnApiKeyLongerThanTheMaximumLength() throws Exception {
        mockMvc.perform(apiRequest(HttpMethod.GET, "/api/v1/issues").header(TestApiKeys.HEADER, "a".repeat(129)))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @MethodSource("protectedEndpoints")
    void rejectsWellFormedButUnknownApiKeys(HttpMethod method, String path) throws Exception {
        String unknownKey = "unknown-key-0123456789abcdef0123456789";

        mockMvc.perform(apiRequest(method, path).header(TestApiKeys.HEADER, unknownKey))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(not(containsString(unknownKey))));
    }

    @Test
    void rejectsKeysThatDifferOnlyInCase() throws Exception {
        mockMvc.perform(apiRequest(HttpMethod.GET, "/api/v1/issues")
                        .header(TestApiKeys.HEADER, TestApiKeys.MANAGEMENT_KEY.toUpperCase()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsRequestsWithMoreThanOneApiKeyHeader() throws Exception {
        mockMvc.perform(apiRequest(HttpMethod.GET, "/api/v1/issues")
                        .header(TestApiKeys.HEADER, TestApiKeys.MANAGEMENT_KEY, TestApiKeys.MANAGEMENT_KEY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsAnApiKeySentInTheAuthorizationHeader() throws Exception {
        mockMvc.perform(apiRequest(HttpMethod.GET, "/api/v1/issues")
                        .header("Authorization", "Bearer " + TestApiKeys.MANAGEMENT_KEY))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @MethodSource("managementEndpoints")
    void forbidsIngestionKeysOnManagementEndpoints(HttpMethod method, String path) throws Exception {
        mockMvc.perform(apiRequest(method, path).header(TestApiKeys.HEADER, TestApiKeys.CHECKOUT_INGESTION_KEY))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.detail").value("The API key does not permit this request."))
                .andExpect(jsonPath("$.instance").value(path));
    }

    @Test
    void forbidsTheManagementKeyOnTheIngestionEndpoint() throws Exception {
        mockMvc.perform(apiRequest(HttpMethod.POST, "/api/v1/events")
                        .header(TestApiKeys.HEADER, TestApiKeys.MANAGEMENT_KEY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Forbidden"));
    }

    @Test
    void deniesUnmappedPathsEvenWithAValidKey() throws Exception {
        mockMvc.perform(apiRequest(HttpMethod.GET, "/api/v1/projects")
                        .header(TestApiKeys.HEADER, TestApiKeys.MANAGEMENT_KEY))
                .andExpect(status().isForbidden());
        mockMvc.perform(apiRequest(HttpMethod.GET, "/api/v1/projects"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void addsSecurityHeadersAndNoSessionCookie() throws Exception {
        mockMvc.perform(apiRequest(HttpMethod.GET, "/api/v1/issues"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void doesNotAllowCrossOriginRequestsByDefault() throws Exception {
        mockMvc.perform(request(HttpMethod.OPTIONS, "/api/v1/issues")
                        .header("Origin", "https://dashboard.example.com")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", TestApiKeys.HEADER))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    private static MockHttpServletRequestBuilder apiRequest(HttpMethod method, String path) {
        MockHttpServletRequestBuilder builder = request(method, path);
        if (method == HttpMethod.POST) {
            builder.contentType(MediaType.APPLICATION_JSON).content(EVENT_BODY);
        }
        return builder;
    }
}
