package com.java2nb.novel.core.utils;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * @author Administrator
 */
public class CookieUtil {

    public static String getCookie(HttpServletRequest request, String key){
        Cookie[] cookies = request.getCookies();
        if(cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookie.getName().equals(key)) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
    public static void setCookie(HttpServletResponse response,String key,String value){
        Cookie cookie = new Cookie(key, value);
        cookie.setPath("/");
        response.addCookie(cookie);
    }

    public static void setCookie(HttpServletResponse response, String key, String value,
                                 int maxAgeSeconds, boolean httpOnly, boolean secure,
                                 String sameSite) {
        Cookie cookie = new Cookie(key, value);
        cookie.setPath("/");
        cookie.setMaxAge(maxAgeSeconds);
        cookie.setHttpOnly(httpOnly);
        cookie.setSecure(secure);
        cookie.setAttribute("SameSite", sameSite);
        response.addCookie(cookie);
    }

}
