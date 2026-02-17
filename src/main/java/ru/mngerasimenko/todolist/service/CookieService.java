package ru.mngerasimenko.todolist.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;

/**
 * Сервис для работы с HTTP cookie.
 * Работает через стандартные HttpServletRequest/HttpServletResponse без Vaadin зависимостей.
 */
@Service
public class CookieService {

    public static final String COOKIE_NAME = "todoAuthId";
    public static final int DAY = 60 * 60 * 24;

    /**
     * Получает значение auth cookie из запроса
     *
     * @param request HTTP запрос
     * @return значение cookie или null
     */
    public String getAuthCookieValue(HttpServletRequest request) {
        return getAuthCookieValue(request, COOKIE_NAME);
    }

    /**
     * Получает значение cookie по имени из запроса
     *
     * @param request HTTP запрос
     * @param name    имя cookie
     * @return значение cookie или null
     */
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

    /**
     * Устанавливает auth cookie через HTTP response
     *
     * @param response  HTTP ответ
     * @param value     значение cookie
     * @param maxAgeDay время жизни в днях
     */
    public void setCookie(HttpServletResponse response, String value, int maxAgeDay) {
        setCookie(response, COOKIE_NAME, value, maxAgeDay);
    }

    /**
     * Устанавливает cookie через HTTP response
     *
     * @param response  HTTP ответ
     * @param name      имя cookie
     * @param value     значение cookie
     * @param maxAgeDay время жизни в днях
     */
    public void setCookie(HttpServletResponse response, String name, String value, int maxAgeDay) {
        if (response == null) return;

        Cookie cookie = createCookie(name, value, maxAgeDay);
        response.addCookie(cookie);
    }

    /**
     * Удаляет auth cookie через HTTP response
     *
     * @param response HTTP ответ
     */
    public void deleteCookie(HttpServletResponse response) {
        deleteCookie(response, COOKIE_NAME);
    }

    /**
     * Удаляет cookie через HTTP response
     *
     * @param response HTTP ответ
     * @param name     имя cookie
     */
    public void deleteCookie(HttpServletResponse response, String name) {
        setCookie(response, name, "", 0);
    }

    /**
     * Создаёт cookie с заданными параметрами
     *
     * @param name      имя cookie
     * @param value     значение cookie
     * @param maxAgeDay время жизни в днях
     * @return настроенный cookie
     */
    public Cookie createCookie(String name, String value, int maxAgeDay) {
        Cookie cookie = new Cookie(name, value);
        cookie.setMaxAge(maxAgeDay * DAY);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        //cookie.setSecure(true); // Включить для HTTPS в production

        return cookie;
    }
}
