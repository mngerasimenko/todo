package ru.mngerasimenko.todolist.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CookieServiceTest {

    @InjectMocks
    private CookieService cookieService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    private static final String TEST_COOKIE_VALUE = "test-auth-id-123";

    // ==================== GET COOKIE TESTS ====================

    @Test
    void getAuthCookieValue_WithExistingCookie_ReturnsCookieValue() {
        Cookie cookie = new Cookie(CookieService.COOKIE_NAME, TEST_COOKIE_VALUE);
        Cookie[] cookies = {cookie};
        when(request.getCookies()).thenReturn(cookies);

        String result = cookieService.getAuthCookieValue(request);

        assertThat(result).isEqualTo(TEST_COOKIE_VALUE);
        verify(request).getCookies();
    }

    @Test
    void getAuthCookieValue_WithNonExistentCookie_ReturnsNull() {
        Cookie cookie = new Cookie("otherCookie", "otherValue");
        Cookie[] cookies = {cookie};
        when(request.getCookies()).thenReturn(cookies);

        String result = cookieService.getAuthCookieValue(request);

        assertThat(result).isNull();
    }

    @Test
    void getAuthCookieValue_WithNullCookies_ReturnsNull() {
        when(request.getCookies()).thenReturn(null);

        String result = cookieService.getAuthCookieValue(request);

        assertThat(result).isNull();
    }

    @Test
    void getAuthCookieValue_WithEmptyCookiesArray_ReturnsNull() {
        when(request.getCookies()).thenReturn(new Cookie[0]);

        String result = cookieService.getAuthCookieValue(request);

        assertThat(result).isNull();
    }

    @Test
    void getAuthCookieValue_WithNullRequest_ReturnsNull() {
        assertThat(cookieService.getAuthCookieValue(null)).isNull();
    }

    @Test
    void getAuthCookieValue_WithMultipleCookies_ReturnsCorrectValue() {
        Cookie[] cookies = {
                new Cookie("cookie1", "value1"),
                new Cookie(CookieService.COOKIE_NAME, TEST_COOKIE_VALUE),
                new Cookie("cookie3", "value3")
        };
        when(request.getCookies()).thenReturn(cookies);

        String result = cookieService.getAuthCookieValue(request);

        assertThat(result).isEqualTo(TEST_COOKIE_VALUE);
    }

    @Test
    void getAuthCookieValue_WithCustomName_ReturnsCorrectValue() {
        String customName = "customCookie";
        String customValue = "custom-value";
        Cookie[] cookies = {
                new Cookie("other", "value"),
                new Cookie(customName, customValue)
        };
        when(request.getCookies()).thenReturn(cookies);

        String result = cookieService.getAuthCookieValue(request, customName);

        assertThat(result).isEqualTo(customValue);
    }

    // ==================== SET COOKIE TESTS ====================

    @Test
    void setCookie_WithValueAndMaxAge_SetsCookieWithCorrectParameters() {
        int maxAgeDays = 7;
        int expectedMaxAgeSeconds = maxAgeDays * CookieService.DAY;

        cookieService.setCookie(response, TEST_COOKIE_VALUE, maxAgeDays);

        ArgumentCaptor<Cookie> cookieCaptor = ArgumentCaptor.forClass(Cookie.class);
        verify(response).addCookie(cookieCaptor.capture());

        Cookie capturedCookie = cookieCaptor.getValue();
        assertThat(capturedCookie).isNotNull();
        assertThat(capturedCookie.getName()).isEqualTo(CookieService.COOKIE_NAME);
        assertThat(capturedCookie.getValue()).isEqualTo(TEST_COOKIE_VALUE);
        assertThat(capturedCookie.getMaxAge()).isEqualTo(expectedMaxAgeSeconds);
        assertThat(capturedCookie.getPath()).isEqualTo("/");
        assertThat(capturedCookie.isHttpOnly()).isTrue();
    }

    @Test
    void setCookie_WithZeroMaxAge_SetsCookieWithZeroMaxAge() {
        cookieService.setCookie(response, TEST_COOKIE_VALUE, 0);

        ArgumentCaptor<Cookie> cookieCaptor = ArgumentCaptor.forClass(Cookie.class);
        verify(response).addCookie(cookieCaptor.capture());

        assertThat(cookieCaptor.getValue().getMaxAge()).isEqualTo(0);
    }

    @Test
    void setCookie_WithEmptyValue_SetsCookieWithEmptyValue() {
        cookieService.setCookie(response, "", 7);

        ArgumentCaptor<Cookie> cookieCaptor = ArgumentCaptor.forClass(Cookie.class);
        verify(response).addCookie(cookieCaptor.capture());

        assertThat(cookieCaptor.getValue().getValue()).isEmpty();
    }

    @Test
    void setCookie_WithNullResponse_DoesNotThrowException() {
        assertThatCode(() -> cookieService.setCookie((HttpServletResponse) null, TEST_COOKIE_VALUE, 7))
                .doesNotThrowAnyException();
    }

    @Test
    void setCookie_WithCustomName_SetsCookieWithCorrectName() {
        String customName = "customCookie";

        cookieService.setCookie(response, customName, TEST_COOKIE_VALUE, 30);

        ArgumentCaptor<Cookie> cookieCaptor = ArgumentCaptor.forClass(Cookie.class);
        verify(response).addCookie(cookieCaptor.capture());

        Cookie capturedCookie = cookieCaptor.getValue();
        assertThat(capturedCookie.getName()).isEqualTo(customName);
        assertThat(capturedCookie.getValue()).isEqualTo(TEST_COOKIE_VALUE);
    }

    @Test
    void setCookie_WithUuidValue_SetsCorrectly() {
        String uuidValue = "811f619a-885f-40b9-a75f-a8864eff1196";

        cookieService.setCookie(response, uuidValue, 7);

        ArgumentCaptor<Cookie> cookieCaptor = ArgumentCaptor.forClass(Cookie.class);
        verify(response).addCookie(cookieCaptor.capture());

        assertThat(cookieCaptor.getValue().getValue()).isEqualTo(uuidValue);
    }

    @Test
    void setCookie_WithLargeMaxAge_DoesNotOverflow() {
        cookieService.setCookie(response, TEST_COOKIE_VALUE, 365);

        ArgumentCaptor<Cookie> cookieCaptor = ArgumentCaptor.forClass(Cookie.class);
        verify(response).addCookie(cookieCaptor.capture());

        assertThat(cookieCaptor.getValue().getMaxAge()).isEqualTo(365 * CookieService.DAY);
    }

    // ==================== DELETE COOKIE TESTS ====================

    @Test
    void deleteCookie_DeletesDefaultCookie() {
        cookieService.deleteCookie(response);

        ArgumentCaptor<Cookie> cookieCaptor = ArgumentCaptor.forClass(Cookie.class);
        verify(response).addCookie(cookieCaptor.capture());

        Cookie capturedCookie = cookieCaptor.getValue();
        assertThat(capturedCookie.getName()).isEqualTo(CookieService.COOKIE_NAME);
        assertThat(capturedCookie.getValue()).isEmpty();
        assertThat(capturedCookie.getMaxAge()).isEqualTo(0);
    }

    @Test
    void deleteCookie_WithCustomName_DeletesCustomCookie() {
        String customName = "customCookie";

        cookieService.deleteCookie(response, customName);

        ArgumentCaptor<Cookie> cookieCaptor = ArgumentCaptor.forClass(Cookie.class);
        verify(response).addCookie(cookieCaptor.capture());

        Cookie capturedCookie = cookieCaptor.getValue();
        assertThat(capturedCookie.getName()).isEqualTo(customName);
        assertThat(capturedCookie.getValue()).isEmpty();
        assertThat(capturedCookie.getMaxAge()).isEqualTo(0);
    }

    // ==================== INTEGRATION TESTS ====================

    @Test
    void deleteThenSetCookie_SequenceTest() {
        cookieService.deleteCookie(response);
        cookieService.setCookie(response, TEST_COOKIE_VALUE, 7);

        ArgumentCaptor<Cookie> cookieCaptor = ArgumentCaptor.forClass(Cookie.class);
        verify(response, times(2)).addCookie(cookieCaptor.capture());

        List<Cookie> capturedCookies = cookieCaptor.getAllValues();
        assertThat(capturedCookies).hasSize(2);

        Cookie deleteCookie = capturedCookies.get(0);
        assertThat(deleteCookie.getValue()).isEmpty();
        assertThat(deleteCookie.getMaxAge()).isEqualTo(0);

        Cookie setCookie = capturedCookies.get(1);
        assertThat(setCookie.getValue()).isEqualTo(TEST_COOKIE_VALUE);
        assertThat(setCookie.getMaxAge()).isEqualTo(7 * CookieService.DAY);
    }

    // ==================== CREATE COOKIE TESTS ====================

    @Test
    void createCookie_ReturnsCorrectlyConfiguredCookie() {
        Cookie cookie = cookieService.createCookie("testName", "testValue", 30);

        assertThat(cookie.getName()).isEqualTo("testName");
        assertThat(cookie.getValue()).isEqualTo("testValue");
        assertThat(cookie.getMaxAge()).isEqualTo(30 * CookieService.DAY);
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.isHttpOnly()).isTrue();
    }
}
