package ru.mngerasimenko.todolist.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.MessageInterpolator;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import ru.mngerasimenko.todolist.util.AcceptLanguageParser;

import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;

/**
 * Выбирает язык сообщений bean validation по заголовку {@code Accept-Language} текущего HTTP-запроса.
 * <p>
 * Язык выбирается тем же {@link AcceptLanguageParser} и из того же набора, что у 429 в
 * {@code RateLimitFilter} и у страницы отписки в {@code EmailUnsubscribeController}: поддерживаемый язык
 * с наибольшим q-весом, иначе {@code ru}. Набор языков и язык по умолчанию держать равными с ними,
 * иначе один заголовок даст ответы на разных языках.
 * <p>
 * <b>Почему не глобальный {@code LocaleResolver}.</b> {@code LocaleContextHolder} и так следует за
 * {@code Accept-Language} (дефолтный {@code AcceptHeaderLocaleResolver} в {@code DispatcherServlet}),
 * и по нему Spring Security переводит тексты ошибок входа: у {@code DaoAuthenticationProvider} свой
 * {@code SpringSecurityMessageSource}, в котором есть русский бандл. Клиент с явным {@code ru} и так
 * получает оттуда русский текст вместо {@code "Invalid email or password"} — {@code GlobalExceptionHandler}
 * узнаёт неверный пароль по английской строке {@code "Bad credentials"} и на русской до подмены не
 * доходит (pre-existing дефект, чинится отдельно). Свой резолвер добавил бы к этому клиента без
 * заголовка — а это весь Android — и клиента с неподдерживаемым языком. Поэтому наше правило
 * применяется только к валидации: здесь оно ничего не ломает за пределами своих сообщений.
 * <p>
 * Вне HTTP-запроса (фоновые потоки, {@code @Async}) заголовка нет — остаётся локаль, которую передал
 * вызывающий, то есть {@code LocaleContextHolder} (по умолчанию — локаль JVM).
 */
final class AcceptLanguageMessageInterpolator implements MessageInterpolator {

    private static final Set<String> SUPPORTED_LANGUAGES = Set.of("ru", "en");

    private static final String DEFAULT_LANGUAGE = "ru";

    /** Имя request-атрибута с уже разобранной локалью — см. {@link #resolveLocale}. */
    private static final String RESOLVED_LOCALE_ATTRIBUTE =
            AcceptLanguageMessageInterpolator.class.getName() + ".LOCALE";

    private final MessageInterpolator delegate;

    AcceptLanguageMessageInterpolator(MessageInterpolator delegate) {
        this.delegate = delegate;
    }

    @Override
    public String interpolate(String messageTemplate, Context context) {
        return interpolate(messageTemplate, context, LocaleContextHolder.getLocale());
    }

    @Override
    public String interpolate(String messageTemplate, Context context, Locale locale) {
        return delegate.interpolate(messageTemplate, context, resolveLocale(locale));
    }

    /**
     * Язык запроса, разобранный один раз на запрос: интерполятор вызывается на каждое нарушение,
     * а нарушений в одном запросе бывает много ({@code /api/todos/reorder} принимает список без
     * верхней границы), тогда как ответ по заголовку от нарушения к нарушению не меняется.
     */
    private static Locale resolveLocale(Locale contextLocale) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return contextLocale;
        }
        // Атрибуты берутся с самого запроса, а не через RequestAttributes: тот бросает
        // IllegalStateException на завершённом запросе, и валидация в этом окне дала бы 500
        // вместо 400. Окно это лишь сужается — переработанный Tomcat'ом запрос бросит тоже,
        // — но живого пути сюда в проекте нет, так что дешевле не открывать его вовсе.
        HttpServletRequest request = servletAttributes.getRequest();
        if (request.getAttribute(RESOLVED_LOCALE_ATTRIBUTE) instanceof Locale cached) {
            return cached;
        }
        Locale locale = Locale.forLanguageTag(AcceptLanguageParser.bestSupportedLanguage(
                acceptLanguage(request), SUPPORTED_LANGUAGES, DEFAULT_LANGUAGE));
        request.setAttribute(RESOLVED_LOCALE_ATTRIBUTE, locale);
        return locale;
    }

    /**
     * Все строки {@code Accept-Language}, склеенные через запятую — так же, как их собирает
     * {@code RateLimitFilter} для 429 и как их получают контроллеры через {@code @RequestHeader String}.
     * По RFC 9110 несколько строк заголовка равны одной; {@code getHeader} отдал бы только первую,
     * и на запросе «de» + «en» язык сообщений валидации разошёлся бы с языком 429.
     */
    private static String acceptLanguage(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders(HttpHeaders.ACCEPT_LANGUAGE);
        return values == null ? null : String.join(",", Collections.list(values));
    }
}
