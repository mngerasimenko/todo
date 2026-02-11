package ru.mngerasimenko.todolist.service;

import com.vaadin.flow.server.VaadinResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.mngerasimenko.todolist.mapper.VaadinServiceWrapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CookieServiceTest {

    @InjectMocks
    private CookieService cookieService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private VaadinResponse response;

    @Mock
    private VaadinServiceWrapper vaadinServiceWrapper;

    private static final String TEST_COOKIE_NAME = "todoAuthId";
    private static final String TEST_COOKIE_VALUE = "test-auth-id-123";


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
        verify(request).getCookies();
    }

    @Test
    void getAuthCookieValue_WithNullCookies_ReturnsNull() {
        when(request.getCookies()).thenReturn(null);

        String result = cookieService.getAuthCookieValue(request);

        assertThat(result).isNull();
        verify(request).getCookies();
    }

    @Test
    void getAuthCookieValue_WithEmptyCookiesArray_ReturnsNull() {
        when(request.getCookies()).thenReturn(new Cookie[0]);

        String result = cookieService.getAuthCookieValue(request);

        assertThat(result).isNull();
        verify(request).getCookies();
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
        verify(request).getCookies();
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
        verify(request).getCookies();
    }

    @Test
    void setCookie_WithValueAndMaxAge_SetsCookieWithCorrectParameters() {
        int maxAgeDays = 7;
        int expectedMaxAgeSeconds = maxAgeDays * CookieService.DAY;
        when(vaadinServiceWrapper.getCurrentResponse()).thenReturn(response);

        cookieService.setCookie(TEST_COOKIE_VALUE, maxAgeDays);

        ArgumentCaptor<Cookie> cookieCaptor = ArgumentCaptor.forClass(Cookie.class);
        verify(response).addCookie(cookieCaptor.capture());

        Cookie capturedCookie = cookieCaptor.getValue();
        assertThat(capturedCookie).isNotNull();
        assertThat(capturedCookie.getName()).isEqualTo(CookieService.COOKIE_NAME);
        assertThat(capturedCookie.getValue()).isEqualTo(TEST_COOKIE_VALUE);
        assertThat(capturedCookie.getMaxAge()).isEqualTo(expectedMaxAgeSeconds);
        assertThat(capturedCookie.getPath()).isEqualTo("/");
        assertThat(capturedCookie.isHttpOnly()).isTrue();
        assertThat(capturedCookie.getSecure()).isFalse();
    }

    @Test
    void setCookie_WithZeroMaxAge_SetsCookieWithZeroMaxAge() {
        when(vaadinServiceWrapper.getCurrentResponse()).thenReturn(response);

        cookieService.setCookie(TEST_COOKIE_VALUE, 0);

        ArgumentCaptor<Cookie> cookieCaptor = ArgumentCaptor.forClass(Cookie.class);
        verify(response).addCookie(cookieCaptor.capture());

        Cookie capturedCookie = cookieCaptor.getValue();
        assertThat(capturedCookie.getMaxAge()).isEqualTo(0);
    }

    @Test
    void setCookie_WithNegativeMaxAge_SetsCookieWithNegativeMaxAge() {
        when(vaadinServiceWrapper.getCurrentResponse()).thenReturn(response);

        cookieService.setCookie(TEST_COOKIE_VALUE, -1);

        ArgumentCaptor<Cookie> cookieCaptor = ArgumentCaptor.forClass(Cookie.class);
        verify(response).addCookie(cookieCaptor.capture());

        Cookie capturedCookie = cookieCaptor.getValue();
        assertThat(capturedCookie.getMaxAge()).isEqualTo(-1 * CookieService.DAY);
    }

    @Test
    void setCookie_WithEmptyValue_SetsCookieWithEmptyValue() {
        when(vaadinServiceWrapper.getCurrentResponse()).thenReturn(response);

        cookieService.setCookie("", 7);

        ArgumentCaptor<Cookie> cookieCaptor = ArgumentCaptor.forClass(Cookie.class);
        verify(response).addCookie(cookieCaptor.capture());

        Cookie capturedCookie = cookieCaptor.getValue();
        assertThat(capturedCookie.getValue()).isEmpty();
    }

    @Test
    void setCookie_WithNullResponse_DoesNotThrowException() {
        when(vaadinServiceWrapper.getCurrentResponse()).thenReturn(null);

        assertThatCode(() -> cookieService.setCookie(TEST_COOKIE_VALUE, 7))
                .doesNotThrowAnyException();

        verify(response, never()).addCookie(any(Cookie.class));
    }

    @Test
    void setCookie_WithCustomName_SetsCookieWithCorrectName() {
        String customName = "customCookie";
        int maxAgeDays = 30;
        when(vaadinServiceWrapper.getCurrentResponse()).thenReturn(response);

        cookieService.setCookie(customName, TEST_COOKIE_VALUE, maxAgeDays);

        ArgumentCaptor<Cookie> cookieCaptor = ArgumentCaptor.forClass(Cookie.class);
        verify(response).addCookie(cookieCaptor.capture());

        Cookie capturedCookie = cookieCaptor.getValue();
        assertThat(capturedCookie.getName()).isEqualTo(customName);
        assertThat(capturedCookie.getValue()).isEqualTo(TEST_COOKIE_VALUE);
    }

    @Test
    void setCookie_WithSpecialCharacters_SetsCorrectly() {
        String specialValue = "811f619a-885f-40b9-a75f-a8864eff1196";
        when(vaadinServiceWrapper.getCurrentResponse()).thenReturn(response);

        cookieService.setCookie(specialValue, 7);

        ArgumentCaptor<Cookie> cookieCaptor = ArgumentCaptor.forClass(Cookie.class);
        verify(response).addCookie(cookieCaptor.capture());

        Cookie capturedCookie = cookieCaptor.getValue();
        assertThat(capturedCookie.getValue()).isEqualTo(specialValue);
    }

    @Test
    void setCookie_WithVeryLargeMaxAge_DoesNotOverflow() {
        int maxAgeDays = 365;
        when(vaadinServiceWrapper.getCurrentResponse()).thenReturn(response);

        cookieService.setCookie(TEST_COOKIE_VALUE, maxAgeDays);

        ArgumentCaptor<Cookie> cookieCaptor = ArgumentCaptor.forClass(Cookie.class);
        verify(response).addCookie(cookieCaptor.capture());

        Cookie capturedCookie = cookieCaptor.getValue();
        int expectedMaxAge = 365 * CookieService.DAY;
        assertThat(capturedCookie.getMaxAge()).isEqualTo(expectedMaxAge);
    }

    @Test
    void deleteCookie_DeletesDefaultCookie() {
        when(vaadinServiceWrapper.getCurrentResponse()).thenReturn(response);

        cookieService.deleteCookie();

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
        when(vaadinServiceWrapper.getCurrentResponse()).thenReturn(response);

        cookieService.deleteCookie(customName);

        ArgumentCaptor<Cookie> cookieCaptor = ArgumentCaptor.forClass(Cookie.class);
        verify(response).addCookie(cookieCaptor.capture());

        Cookie capturedCookie = cookieCaptor.getValue();
        assertThat(capturedCookie.getName()).isEqualTo(customName);
        assertThat(capturedCookie.getValue()).isEmpty();
        assertThat(capturedCookie.getMaxAge()).isEqualTo(0);
    }

    @Test
    void setAndGetCookie_IntegrationTest() {
        int maxAgeDays = 7;
        when(vaadinServiceWrapper.getCurrentResponse()).thenReturn(response);

        cookieService.setCookie(TEST_COOKIE_VALUE, maxAgeDays);

        ArgumentCaptor<Cookie> cookieCaptor = ArgumentCaptor.forClass(Cookie.class);
        verify(response).addCookie(cookieCaptor.capture());

        Cookie capturedCookie = cookieCaptor.getValue();
        assertThat(capturedCookie.getName()).isEqualTo(CookieService.COOKIE_NAME);
        assertThat(capturedCookie.getValue()).isEqualTo(TEST_COOKIE_VALUE);
    }

    @Test
    void deleteThenSetCookie_SequenceTest() {
        when(vaadinServiceWrapper.getCurrentResponse()).thenReturn(response);

        cookieService.deleteCookie();
        cookieService.setCookie(TEST_COOKIE_VALUE, 7);

        ArgumentCaptor<Cookie> cookieCaptor = ArgumentCaptor.forClass(Cookie.class);
        verify(response, times(2)).addCookie(cookieCaptor.capture());

        List<Cookie> capturedCookies = cookieCaptor.getAllValues();
        assertThat(capturedCookies).hasSize(2);

        Cookie deleteCookie = capturedCookies.get(0);
        assertThat(deleteCookie.getName()).isEqualTo(CookieService.COOKIE_NAME);
        assertThat(deleteCookie.getValue()).isEmpty();
        assertThat(deleteCookie.getMaxAge()).isEqualTo(0);

        Cookie setCookie = capturedCookies.get(1);
        assertThat(setCookie.getName()).isEqualTo(CookieService.COOKIE_NAME);
        assertThat(setCookie.getValue()).isEqualTo(TEST_COOKIE_VALUE);
        assertThat(setCookie.getMaxAge()).isEqualTo(7 * CookieService.DAY);
    }
}