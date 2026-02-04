package ru.mngerasimenko.todolist.service;

import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.VaadinService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

@Service
public class CookieService {
    public static final String COOKIE_NAME = "todoAuthId";
    public static final int DAY = 60 * 60 * 24;

    public String getAuthCookieValue(HttpServletRequest request) {
        return getAuthCookieValue(request, COOKIE_NAME);
    }

    public String getAuthCookieValue(HttpServletRequest request, String name) {
        if (request == null) return null;

        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (name.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    public void setCookie(String value, int maxAgeDay) {
        setCookie(COOKIE_NAME, value, maxAgeDay);
    }

    public void setCookie(String name, String value, int maxAgeMonth) {
        VaadinResponse response = VaadinService.getCurrentResponse();
        if (response == null) return;

        Cookie cookie = new Cookie(name, value);
        cookie.setMaxAge(maxAgeMonth * DAY);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setSecure(true);

        response.addCookie(cookie);
    }

    public void deleteCookie() {
        deleteCookie(COOKIE_NAME);
    }

    public void deleteCookie(String name) {
        setCookie(name, "", 0);
    }

}
