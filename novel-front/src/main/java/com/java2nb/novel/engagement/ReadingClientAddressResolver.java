package com.java2nb.novel.engagement;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ReadingClientAddressResolver {

    private static final Pattern IP_ADDRESS_LITERAL = Pattern.compile("[0-9A-Fa-f:.]+");

    private final ReadingEngagementProperties properties;

    public ReadingClientAddressResolver(ReadingEngagementProperties properties) {
        this.properties = properties;
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        if (!properties.trustedProxyAddresses().contains(remoteAddress)) {
            return remoteAddress;
        }

        String realIpHeader = request.getHeader("X-Real-IP");
        if (realIpHeader == null || realIpHeader.length() > 45
            || !IP_ADDRESS_LITERAL.matcher(realIpHeader).matches()) {
            return remoteAddress;
        }
        return realIpHeader;
    }
}
