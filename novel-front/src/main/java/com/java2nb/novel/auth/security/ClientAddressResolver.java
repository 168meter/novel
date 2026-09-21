package com.java2nb.novel.auth.security;

import jakarta.servlet.http.HttpServletRequest;

public interface ClientAddressResolver {
    String resolve(HttpServletRequest request);
}
