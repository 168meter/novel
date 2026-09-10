package com.java2nb.novel.engagement;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ReadingClientAddressResolverTest {

    private final ReadingClientAddressResolver resolver =
        new ReadingClientAddressResolver(new ReadingEngagementProperties());

    @Test
    void ignoresForgedHeaderFromUntrustedRemoteAddress() {
        MockHttpServletRequest request = request("203.0.113.10", "198.51.100.20");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.10");
    }

    @Test
    void acceptsValidOverwrittenHeaderFromTrustedLoopbackProxy() {
        MockHttpServletRequest request = request("127.0.0.1", "198.51.100.20");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.20");
    }

    @Test
    void rejectsNonLiteralHeaderFromTrustedLoopbackProxy() {
        MockHttpServletRequest request = request("127.0.0.1", "attacker.example");

        assertThat(resolver.resolve(request)).isEqualTo("127.0.0.1");
    }

    @Test
    void rejectsMalformedIpLiteralsFromTrustedLoopbackProxy() {
        for (String malformedHeader : new String[] {":", "...", "1.2.3.999"}) {
            MockHttpServletRequest request = request("127.0.0.1", malformedHeader);

            assertThat(resolver.resolve(request)).isEqualTo("127.0.0.1");
        }
    }

    private MockHttpServletRequest request(String remoteAddress, String realIpHeader) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        request.addHeader("X-Real-IP", realIpHeader);
        return request;
    }
}
