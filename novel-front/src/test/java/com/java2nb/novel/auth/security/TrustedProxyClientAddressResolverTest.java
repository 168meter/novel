package com.java2nb.novel.auth.security;

import com.java2nb.novel.auth.config.AuthSecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class TrustedProxyClientAddressResolverTest {
    @Test void directRequestIgnoresSpoofedForwardedHeaders() {
        var resolver = resolver(Set.of("10.0.0.0/8"));
        var request = request("203.0.113.7", "198.51.100.9");
        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
        assertThat(resolver.resolve(request("2001:0db8:0:0:0:0:0:7", "2001:db9::8")))
            .isEqualTo("2001:db8:0:0:0:0:0:7");
    }

    @Test void trustedProxyCidrMaySupplyOverwrittenRealIp() {
        var resolver = resolver(Set.of("10.0.0.0/8", "2001:db8::/32"));
        assertThat(resolver.resolve(request("10.2.3.4", "198.51.100.9")))
            .isEqualTo("198.51.100.9");
        assertThat(resolver.resolve(request("2001:db8::7", "2001:db9::8")))
            .isEqualTo("2001:db9:0:0:0:0:0:8");
    }

    @Test void malformedHeaderFallsBackAndInvalidCidrFailsFast() {
        var resolver = resolver(Set.of("10.0.0.0/8"));
        assertThat(resolver.resolve(request("10.2.3.4", "attacker.example"))).isEqualTo("10.2.3.4");
        assertThatThrownBy(() -> resolver(Set.of("10.0.0.0/99")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static TrustedProxyClientAddressResolver resolver(Set<String> trusted) {
        AuthSecurityProperties properties = new AuthSecurityProperties();
        properties.setTrustedProxyAddresses(trusted);
        return new TrustedProxyClientAddressResolver(properties);
    }

    private static MockHttpServletRequest request(String remote, String realIp) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remote);
        request.addHeader("X-Real-IP", realIp);
        request.addHeader("X-Forwarded-For", "192.0.2.123");
        return request;
    }
}
