package com.java2nb.novel.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java2nb.novel.core.advice.CommonExceptionHandler;
import com.java2nb.novel.core.utils.ThreadLocalUtil;
import com.java2nb.novel.engagement.ReadingClientAddressResolver;
import com.java2nb.novel.engagement.ReadingEngagementService;
import com.java2nb.novel.engagement.ReadingHeartbeatOutcome;
import com.java2nb.novel.engagement.ReadingHeartbeatRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReadingEngagementControllerTest {

    private static final String PAGE_VISIT_ID = "0123456789abcdef0123456789abcdef";
    private static final String VALID_JSON = """
        {"bookId":42,"chapterId":7,"pageVisitId":"0123456789abcdef0123456789abcdef","sequence":3}
        """;

    private ReadingEngagementService engagementService;
    private ReadingClientAddressResolver clientAddressResolver;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        engagementService = mock(ReadingEngagementService.class);
        clientAddressResolver = mock(ReadingClientAddressResolver.class);
        ReadingEngagementController controller =
            new ReadingEngagementController(engagementService, clientAddressResolver);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new CommonExceptionHandler())
            .build();
    }

    @Test
    void validHeartbeatUsesBrowserIdentityAndTrustedClientAddress() throws Exception {
        ReadingHeartbeatRequest expectedRequest = new ReadingHeartbeatRequest(42L, 7L, PAGE_VISIT_ID, 3L);
        when(clientAddressResolver.resolve(any(HttpServletRequest.class))).thenReturn("203.0.113.9");
        when(engagementService.handle(expectedRequest, "browser-user-mark", "203.0.113.9"))
            .thenReturn(ReadingHeartbeatOutcome.ACCEPTED);

        try (MockedStatic<ThreadLocalUtil> threadLocal = mockStatic(ThreadLocalUtil.class)) {
            threadLocal.when(ThreadLocalUtil::getClientId).thenReturn("browser-user-mark");

            MvcResult result = mockMvc.perform(validHeartbeat())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("ACCEPTED"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                .andReturn();

            assertPrivacySafeBody(result.getResponse().getContentAsString());
        }

        verify(clientAddressResolver).resolve(any(HttpServletRequest.class));
        verify(engagementService).handle(expectedRequest, "browser-user-mark", "203.0.113.9");
    }

    @Test
    void browserHeartbeatWithoutAcceptHeaderStillReturnsJson() throws Exception {
        when(clientAddressResolver.resolve(any(HttpServletRequest.class))).thenReturn("203.0.113.9");
        when(engagementService.handle(any(ReadingHeartbeatRequest.class), any(), any()))
            .thenReturn(ReadingHeartbeatOutcome.ACCEPTED);

        try (MockedStatic<ThreadLocalUtil> threadLocal = mockStatic(ThreadLocalUtil.class)) {
            threadLocal.when(ThreadLocalUtil::getClientId).thenReturn("browser-user-mark");
            mockMvc.perform(post("/engagement/reading/heartbeat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_JSON))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                    .contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.outcome").value("ACCEPTED"));
        }
    }

    @Test
    void snowflakeIdStringsBindToLongWithoutPrecisionLoss() throws Exception {
        long bookId = 2055879962859147264L;
        long chapterId = 2055880123456789012L;
        ReadingHeartbeatRequest expectedRequest =
            new ReadingHeartbeatRequest(bookId, chapterId, PAGE_VISIT_ID, 1L);
        when(clientAddressResolver.resolve(any(HttpServletRequest.class))).thenReturn("203.0.113.9");
        when(engagementService.handle(expectedRequest, "browser-user-mark", "203.0.113.9"))
            .thenReturn(ReadingHeartbeatOutcome.ACCEPTED);

        try (MockedStatic<ThreadLocalUtil> threadLocal = mockStatic(ThreadLocalUtil.class)) {
            threadLocal.when(ThreadLocalUtil::getClientId).thenReturn("browser-user-mark");

            mockMvc.perform(post("/engagement/reading/heartbeat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"bookId":"2055879962859147264","chapterId":"2055880123456789012",
                         "pageVisitId":"0123456789abcdef0123456789abcdef","sequence":1}
                        """))
                .andExpect(status().isOk());
        }

        verify(engagementService)
            .handle(expectedRequest, "browser-user-mark", "203.0.113.9");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"bookId\":0,\"chapterId\":7,\"pageVisitId\":\"0123456789abcdef0123456789abcdef\",\"sequence\":3}",
        "{\"bookId\":42,\"chapterId\":7,\"pageVisitId\":\"0123\",\"sequence\":3}",
        "{\"bookId\":42,\"chapterId\":7,\"pageVisitId\":\"g123456789abcdef0123456789abcdef\",\"sequence\":3}",
        "{\"bookId\":42,\"chapterId\":7,\"pageVisitId\":\"0123456789abcdef0123456789abcdef\",\"sequence\":0}",
        "{\"bookId\":42,\"chapterId\":7,\"pageVisitId\":\"0123456789abcdef0123456789abcdef\",\"sequence\":10001}",
        "{\"bookId\":\"9223372036854775808\",\"chapterId\":7,\"pageVisitId\":\"0123456789abcdef0123456789abcdef\",\"sequence\":3}"
    })
    void malformedHeartbeatReturnsBadRequestWithoutCallingService(String json) throws Exception {
        mockMvc.perform(post("/engagement/reading/heartbeat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isBadRequest())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

        verifyNoInteractions(engagementService, clientAddressResolver);
    }

    @ParameterizedTest
    @MethodSource("heartbeatHttpSemantics")
    void outcomeUsesExplicitHttpStatusAndPublicOutcome(
        ReadingHeartbeatOutcome outcome, int expectedStatus, String expectedPublicOutcome
    ) throws Exception {
        ReadingHeartbeatRequest expectedRequest = new ReadingHeartbeatRequest(42L, 7L, PAGE_VISIT_ID, 3L);
        when(clientAddressResolver.resolve(any(HttpServletRequest.class))).thenReturn("203.0.113.9");
        when(engagementService.handle(expectedRequest, "browser-user-mark", "203.0.113.9"))
            .thenReturn(outcome);

        try (MockedStatic<ThreadLocalUtil> threadLocal = mockStatic(ThreadLocalUtil.class)) {
            threadLocal.when(ThreadLocalUtil::getClientId).thenReturn("browser-user-mark");
            MvcResult result = mockMvc.perform(validHeartbeat())
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.data.outcome").value(expectedPublicOutcome))
                .andReturn();
            assertPrivacySafeBody(result.getResponse().getContentAsString());
        }

        verify(engagementService)
            .handle(expectedRequest, "browser-user-mark", "203.0.113.9");
    }

    private static Stream<Arguments> heartbeatHttpSemantics() {
        return Stream.of(
            Arguments.of(ReadingHeartbeatOutcome.ACCEPTED, 200, "ACCEPTED"),
            Arguments.of(ReadingHeartbeatOutcome.DUPLICATE, 200, "DUPLICATE"),
            Arguments.of(ReadingHeartbeatOutcome.TOO_EARLY, 200, "TOO_EARLY"),
            Arguments.of(ReadingHeartbeatOutcome.DAILY_CAP_REACHED, 200, "DAILY_CAP"),
            Arguments.of(ReadingHeartbeatOutcome.SESSION_RATE_LIMITED, 429, "RATE_LIMIT"),
            Arguments.of(ReadingHeartbeatOutcome.IP_RATE_LIMITED, 429, "RATE_LIMIT"),
            Arguments.of(ReadingHeartbeatOutcome.INVALID_PAGE, 409, "INVALID_PAGE"),
            Arguments.of(ReadingHeartbeatOutcome.REDIS_ERROR, 503, "REDIS_ERROR")
        );
    }

    private MockHttpServletRequestBuilder validHeartbeat() {
        return post("/engagement/reading/heartbeat")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .content(VALID_JSON);
    }

    private void assertPrivacySafeBody(String body) throws Exception {
        JsonNode json = new ObjectMapper().readTree(body);
        assertThat(json.path("code").asInt()).isEqualTo(200);
        assertThat(json.path("msg").asText()).isEqualTo("SUCCESS");
        assertThat(body)
            .doesNotContain("browser-user-mark", "203.0.113.9", "session-hash", "ip-hmac");
    }
}
