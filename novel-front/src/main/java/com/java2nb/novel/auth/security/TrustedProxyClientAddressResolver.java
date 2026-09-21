package com.java2nb.novel.auth.security;

import com.java2nb.novel.auth.config.AuthSecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class TrustedProxyClientAddressResolver implements ClientAddressResolver {
    private final List<Network> trusted;

    public TrustedProxyClientAddressResolver(AuthSecurityProperties properties) {
        this.trusted = properties.getTrustedProxyAddresses().stream().map(Network::parse).toList();
    }

    @Override public String resolve(HttpServletRequest request) {
        byte[] remote = literal(request.getRemoteAddr());
        if (remote == null || trusted.stream().noneMatch(network -> network.contains(remote))) {
            return canonical(remote, request.getRemoteAddr());
        }
        String forwarded = request.getHeader("X-Real-IP");
        byte[] client = literal(forwarded);
        if (client == null) return canonical(remote, request.getRemoteAddr());
        return canonical(client, request.getRemoteAddr());
    }

    private record Network(byte[] address, int bits) {
        static Network parse(String value) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Trusted proxy CIDR is required");
            String[] parts = value.trim().split("/", -1);
            if (parts.length > 2) throw new IllegalArgumentException("Invalid trusted proxy CIDR");
            byte[] address = literal(parts[0]);
            if (address == null) throw new IllegalArgumentException("Invalid trusted proxy IP literal");
            int bits = address.length * 8;
            if (parts.length == 2) {
                try { bits = Integer.parseInt(parts[1]); }
                catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid trusted proxy CIDR", invalid); }
            }
            if (bits < 0 || bits > address.length * 8) throw new IllegalArgumentException("Invalid trusted proxy CIDR");
            return new Network(address, bits);
        }
        boolean contains(byte[] candidate) {
            if (candidate.length != address.length) return false;
            int whole = bits / 8, remaining = bits % 8;
            for (int i = 0; i < whole; i++) if (candidate[i] != address[i]) return false;
            if (remaining == 0) return true;
            int mask = 0xff << (8 - remaining);
            return (candidate[whole] & mask) == (address[whole] & mask);
        }
    }

    private static byte[] literal(String value) {
        if (value == null || value.isBlank() || value.length() > 45) return null;
        if (value.indexOf(':') < 0 && !validIpv4(value)) return null;
        if (value.indexOf(':') >= 0 && !value.matches("[0-9a-fA-F:.]+")) return null;
        try { return InetAddress.getByName(value).getAddress(); }
        catch (UnknownHostException invalid) { return null; }
    }

    private static String canonical(byte[] address, String fallback) {
        if (address == null) return fallback;
        try { return InetAddress.getByAddress(address).getHostAddress(); }
        catch (UnknownHostException impossible) { return fallback; }
    }

    private static boolean validIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (!part.matches("\\d{1,3}")) return false;
            if (Integer.parseInt(part) > 255) return false;
        }
        return true;
    }
}
