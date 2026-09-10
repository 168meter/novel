package com.java2nb.novel.engagement;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class ReadingClientAddressResolver {

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
            || !isIpLiteral(realIpHeader)) {
            return remoteAddress;
        }
        return realIpHeader;
    }

    /**
     * Validates literal syntax locally so malformed header values never trigger name resolution.
     */
    private boolean isIpLiteral(String value) {
        return isIpv4(value) || isIpv6(value);
    }

    private boolean isIpv4(String value) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (!isIpv4Octet(octet)) {
                return false;
            }
        }
        return true;
    }

    private boolean isIpv4Octet(String value) {
        if (value.isEmpty() || value.length() > 3) {
            return false;
        }
        int numericValue = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
            numericValue = numericValue * 10 + character - '0';
        }
        return numericValue <= 255;
    }

    private boolean isIpv6(String value) {
        int compressedMarker = value.indexOf("::");
        if (compressedMarker >= 0) {
            if (value.indexOf("::", compressedMarker + 1) >= 0) {
                return false;
            }
            String left = value.substring(0, compressedMarker);
            String right = value.substring(compressedMarker + 2);
            if (hasSegmentBoundaryColon(left) || hasSegmentBoundaryColon(right)) {
                return false;
            }
            int units = ipv6Units(segments(left), segments(right), true);
            return units >= 0 && units < 8;
        }

        if (value.indexOf(':') < 0 || hasSegmentBoundaryColon(value)) {
            return false;
        }
        return ipv6Units(segments(value), new String[0], false) == 8;
    }

    private boolean hasSegmentBoundaryColon(String value) {
        return !value.isEmpty() && (value.startsWith(":") || value.endsWith(":"));
    }

    private String[] segments(String value) {
        return value.isEmpty() ? new String[0] : value.split(":", -1);
    }

    private int ipv6Units(String[] leftSegments, String[] rightSegments, boolean compressed) {
        int totalSegments = leftSegments.length + rightSegments.length;
        int units = 0;
        for (int index = 0; index < totalSegments; index++) {
            String segment = index < leftSegments.length
                ? leftSegments[index]
                : rightSegments[index - leftSegments.length];
            if (segment.indexOf('.') >= 0) {
                if (index != totalSegments - 1 || (compressed && rightSegments.length == 0)
                    || !isIpv4(segment)) {
                    return -1;
                }
                units += 2;
            } else if (isIpv6Hextet(segment)) {
                units++;
            } else {
                return -1;
            }
        }
        return units;
    }

    private boolean isIpv6Hextet(String value) {
        if (value.isEmpty() || value.length() > 4) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= '0' && character <= '9')
                || (character >= 'a' && character <= 'f')
                || (character >= 'A' && character <= 'F'))) {
                return false;
            }
        }
        return true;
    }
}
