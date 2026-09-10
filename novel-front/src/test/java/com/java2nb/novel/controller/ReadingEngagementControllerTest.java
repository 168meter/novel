package com.java2nb.novel.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java2nb.novel.core.utils.ThreadLocalUtil;
import com.java2nb.novel.engagement.ReadingClientAddressResolver;
import com.java2nb.novel.engagement.ReadingEngagementService;
import com.java2nb.novel.engagement.ReadingHeartbeatOutcome;
import com.java2nb.novel.engagement.ReadingHeartbeatRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
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
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                .andReturn();

            assertPublicSuccessBody(result.getResponse().getContentAsString());
        }

        verify(clientAddressResolver).resolve(any(HttpServletRequest.class));
        verify(engagementService).handle(expectedRequest, "browser-user-mark", "203.0.113.9");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"bookId\":0,\"chapterId\":7,\"pageVisitId\":\"0123456789abcdef0123456789abcdef\",\"sequence\":3}",
        "{\"bookId\":42,\"chapterId\":7,\"pageVisitId\":\"0123\",\"sequence\":3}",
        "{\"bookId\":42,\"chapterId\":7,\"pageVisitId\":\"g123456789abcdef0123456789abcdef\",\"sequence\":3}",
        "{\"bookId\":42,\"chapterId\":7,\"pageVisitId\":\"0123456789abcdef0123456789abcdef\",\"sequence\":0}",
        "{\"bookId\":42,\"chapterId\":7,\"pageVisitId\":\"0123456789abcdef0123456789abcdef\",\"sequence\":10001}"
    })
    void malformedHeartbeatReturnsBadRequestWithoutCallingService(String json) throws Exception {
        mockMvc.perform(post("/engagement/reading/heartbeat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(engagementService, clientAddressResolver);
    }

    @Test
    void everyInternalOutcomeReturnsTheSamePrivacySafeBody() throws Exception {
        ReadingHeartbeatRequest expectedRequest = new ReadingHeartbeatRequest(42L, 7L, PAGE_VISIT_ID, 3L);
        when(clientAddressResolver.resolve(any(HttpServletRequest.class))).thenReturn("203.0.113.9");
        List<String> responseBodies = new ArrayList<>();

        try (MockedStatic<ThreadLocalUtil> threadLocal = mockStatic(ThreadLocalUtil.class)) {
            threadLocal.when(ThreadLocalUtil::getClientId).thenReturn("browser-user-mark");
            for (ReadingHeartbeatOutcome outcome : ReadingHeartbeatOutcome.values()) {
                when(engagementService.handle(expectedRequest, "browser-user-mark", "203.0.113.9"))
                    .thenReturn(outcome);

                MvcResult result = mockMvc.perform(validHeartbeat())
                    .andExpect(status().isOk())
                    .andReturn();
                responseBodies.add(result.getResponse().getContentAsString());
            }
        }

        assertThat(responseBodies).hasSize(ReadingHeartbeatOutcome.values().length);
        assertThat(responseBodies).containsOnly(responseBodies.getFirst());
        assertPublicSuccessBody(responseBodies.getFirst());
        verify(engagementService, times(ReadingHeartbeatOutcome.values().length))
            .handle(expectedRequest, "browser-user-mark", "203.0.113.9");
    }

    private MockHttpServletRequestBuilder validHeartbeat() {
        return post("/engagement/reading/heartbeat")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .content(VALID_JSON);
    }

    private void assertPublicSuccessBody(String body) throws Exception {
        JsonNode json = new ObjectMapper().readTree(body);
        assertThat(json.path("code").asInt()).isEqualTo(200);
        assertThat(json.path("msg").asText()).isEqualTo("SUCCESS");
        assertThat(json.path("data").isNull()).isTrue();
        assertThat(body)
            .doesNotContain("ACCEPTED", "DUPLICATE", "INVALID_PAGE", "RATE_LIMITED", "DAILY_CAP", "REDIS_ERROR")
            .doesNotContain("browser-user-mark", "203.0.113.9", "session-hash", "ip-hmac");
    }
}
