package ru.mngerasimenko.todolist.util;

import java.util.Locale;
import java.util.Set;

/**
 * Разбор HTTP-заголовка {@code Accept-Language} (RFC 9110 §12.5.4).
 *
 * <p>Заголовок приходит от клиента и на публичных эндпоинтах — от неаутентифицированного,
 * поэтому разбор здесь ручной, а не через {@code Locale.LanguageRange.parse}. У JDK-реализации
 * два дефекта, из-за которых она непригодна на таком входе:
 * <ul>
 *   <li>{@code parse("-")} бросает {@code ArrayIndexOutOfBoundsException}, а не
 *       {@code IllegalArgumentException} — обычный {@code catch (IllegalArgumentException)}
 *       его не ловит, и враждебный заголовок превращал ответ в HTTP 500;</li>
 *   <li>разбор компилирует regex на каждый суб-тег, поэтому стоит порядка миллисекунд на
 *       заголовке в 8 КБ (дефолтный потолок Tomcat) — на порядки дороже, чем разбор здесь,
 *       и заказать это может кто угодно, без всякой авторизации.</li>
 * </ul>
 *
 * <p>Разбор здесь не бросает исключений вовсе: любой непонятный элемент просто пропускается.
 * Стоимость линейна по длине заголовка (порядка десятка наносекунд на байт): к разбору
 * допускаются первые {@link #MAX_LANGUAGE_RANGES} непустых элементов, но пустые пропускаются
 * бесплатно, поэтому число итераций ограничено не лимитом, а длиной заголовка — на предельных
 * 8 КБ это десятки микросекунд — даже в худшем для нас случае (заголовок из одних запятых)
 * вдвое дешевле того же входа в JDK-разборе, а на осмысленном заголовке дешевле в сотни раз.
 *
 * <p>Из нескольких языков выбирается не первый в списке, а приемлемый с наибольшим q-весом
 * ({@code "ru;q=0.1,en;q=0.9"} → {@code en}); {@code q=0} по RFC 9110 означает «неприемлемо»
 * и такой язык не выбирается никогда.
 */
public final class AcceptLanguageParser {

    /**
     * Максимум разбираемых элементов заголовка. Реальные клиенты присылают единицы языков;
     * всё сверх лимита — признак атаки, а не браузера, и обрабатывать его до конца незачем.
     */
    public static final int MAX_LANGUAGE_RANGES = 16;

    /**
     * Допустимая длина primary language subtag. BCP-47 разрешает до 8 букв, но реальные коды
     * ISO 639 — двух- или трёхбуквенные, и то же подмножество принимает
     * {@code LocaleValidation.PATTERN} на DTO-полях. Держим границы едиными, чтобы primary
     * subtag разобранного тега проходил ту же валидацию, что и присланный клиентом явно.
     */
    private static final int MIN_PRIMARY_SUBTAG_LENGTH = 2;
    private static final int MAX_PRIMARY_SUBTAG_LENGTH = 3;

    /** Предел длины суб-тега (script/region/variant) по BCP-47. */
    private static final int MAX_SUBTAG_LENGTH = 8;

    /**
     * Предел длины тега целиком. Число суб-тегов BCP-47 не ограничивает, поэтому без этой
     * границы клиент мог прислать «тег» в тысячи символов и получить его же обратно —
     * значение, которое не влезет ни в одну колонку БД. 35 символов — размер буфера,
     * рекомендованный RFC 5646 §4.4.1 для хранения language tag.
     */
    private static final int MAX_TAG_LENGTH = 35;

    /** Вес элемента без параметра {@code q} — 1.0 по RFC 9110. */
    private static final double DEFAULT_QUALITY = 1.0;

    /** Длина самого длинного легального qvalue: {@code "0.123"} / {@code "1.000"}. */
    private static final int MAX_QUALITY_LENGTH = 5;

    private AcceptLanguageParser() {
    }

    /**
     * Возвращает полный language tag с наибольшим q-весом в нижнем регистре
     * ({@code "en-US,en;q=0.9"} → {@code "en-us"}), либо {@code null}, если заголовок пуст
     * или ни один элемент не является well-formed BCP-47 тегом (сюда же попадают
     * wildcard {@code "*"} и мусор вроде {@code "-"}).
     * <p>
     * Тег не длиннее {@link #MAX_TAG_LENGTH}, но это всё ещё больше, чем
     * {@code LocaleValidation.MAX_LENGTH} (8): {@code "zh-Hant-TW"} — валидный тег в 10 символов.
     * Вызывающий, который пишет результат в узкое поле, обязан сузить его сам — например,
     * до {@link #primarySubtagOf(String)}, который по контракту укладывается в две-три буквы.
     */
    public static String bestLanguageTag(String acceptLanguage) {
        return bestRange(acceptLanguage, null);
    }

    /**
     * Возвращает primary subtag языка с наибольшим q-весом среди {@code supported}
     * ({@code "en-GB"} матчится как {@code "en"}), либо {@code fallback}, если ни один
     * поддерживаемый язык не приемлем для клиента.
     *
     * @param supported поддерживаемые языки в нижнем регистре, например {@code Set.of("ru", "en")}
     */
    public static String bestSupportedLanguage(String acceptLanguage, Set<String> supported, String fallback) {
        String tag = bestRange(acceptLanguage, supported);
        return tag == null ? fallback : primarySubtagOf(tag);
    }

    /**
     * Отрезает от language tag всё после первого дефиса: {@code "en-gb"} → {@code "en"}.
     * {@code null} на входе даёт {@code null} — метод принимает результат
     * {@link #bestLanguageTag(String)} как есть, без предварительной проверки.
     */
    public static String primarySubtagOf(String languageTag) {
        if (languageTag == null) {
            return null;
        }
        int dash = languageTag.indexOf('-');
        return dash < 0 ? languageTag : languageTag.substring(0, dash);
    }

    /**
     * Общий проход по заголовку: возвращает well-formed тег с наибольшим q-весом.
     * <p>
     * Лимит {@link #MAX_LANGUAGE_RANGES} тратится только на непустые элементы: заголовок из одних
     * запятых — это не шестнадцать языков, и глушить им язык, идущий следом, незачем. Непустой,
     * но битый элемент лимит расходует: столько мусора подряд браузеры не присылают.
     *
     * @param supported если не {@code null} — рассматриваются только элементы, чей primary subtag
     *                  входит в набор
     */
    private static String bestRange(String acceptLanguage, Set<String> supported) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return null;
        }
        String best = null;
        double bestWeight = 0.0;
        int from = 0;
        int parsed = 0;
        while (parsed < MAX_LANGUAGE_RANGES && from < acceptLanguage.length()) {
            int comma = acceptLanguage.indexOf(',', from);
            int end = (comma < 0) ? acceptLanguage.length() : comma;

            if (!isBlank(acceptLanguage, from, end)) {
                parsed++;
                int semicolon = indexOf(acceptLanguage, ';', from, end);
                String tag = languageTag(acceptLanguage, from, semicolon);
                // Порядок условий тут несущий: supported — immutable Set, а его contains(null)
                // бросает NPE. Проверка tag != null обязана стоять первой.
                if (tag != null && (supported == null || supported.contains(primarySubtagOf(tag)))) {
                    double weight = (semicolon < end)
                            ? parseQuality(acceptLanguage, semicolon + 1, end)
                            : DEFAULT_QUALITY;
                    if (weight > bestWeight) {
                        bestWeight = weight;
                        best = tag;
                    }
                }
            }

            if (comma < 0) {
                break;
            }
            from = comma + 1;
        }
        return best;
    }

    /**
     * Достаёт well-formed language tag из участка {@code [from, end)} в нижнем регистре,
     * либо {@code null}, если тег ill-formed. Well-formed по BCP-47 в принимаемом нами
     * подмножестве — это primary subtag из {@link #MIN_PRIMARY_SUBTAG_LENGTH}..{@link
     * #MAX_PRIMARY_SUBTAG_LENGTH} букв, любое число суб-тегов по 1..{@link #MAX_SUBTAG_LENGTH}
     * букв или цифр через дефис, и не длиннее {@link #MAX_TAG_LENGTH} целиком.
     * Проверка ручная и линейная: она дешевле, чем {@code Pattern} с аллокацией матчера
     * на каждый элемент чужого заголовка.
     */
    private static String languageTag(String header, int from, int end) {
        int start = from;
        while (start < end && Character.isWhitespace(header.charAt(start))) {
            start++;
        }
        int stop = end;
        while (stop > start && Character.isWhitespace(header.charAt(stop - 1))) {
            stop--;
        }
        if (stop == start || stop - start > MAX_TAG_LENGTH) {
            return null;
        }

        int subtagLength = 0;
        boolean firstSubtag = true;
        for (int i = start; i <= stop; i++) {
            boolean atBoundary = (i == stop) || header.charAt(i) == '-';
            if (!atBoundary) {
                char c = header.charAt(i);
                boolean allowed = firstSubtag ? isAsciiLetter(c) : (isAsciiLetter(c) || isAsciiDigit(c));
                if (!allowed) {
                    return null;
                }
                subtagLength++;
                continue;
            }
            if (firstSubtag) {
                if (subtagLength < MIN_PRIMARY_SUBTAG_LENGTH || subtagLength > MAX_PRIMARY_SUBTAG_LENGTH) {
                    return null;
                }
                firstSubtag = false;
            } else if (subtagLength < 1 || subtagLength > MAX_SUBTAG_LENGTH) {
                return null;
            }
            subtagLength = 0;
        }
        return header.substring(start, stop).toLowerCase(Locale.ROOT);
    }

    /**
     * Достаёт q-вес из параметров элемента (участок после первой {@code ';'}).
     * Отсутствующий параметр даёт {@link #DEFAULT_QUALITY}; битое значение — 0.0, то есть
     * элемент не выбирается, но и разбор не роняет.
     * <p>
     * Именем параметра считается только точное {@code q}: раньше вес перехватывал любой
     * параметр, в котором встречалась буква q со знаком равенства, и {@code "en;seq=0.1"}
     * молча делал английский почти неприемлемым.
     */
    private static double parseQuality(String header, int from, int end) {
        int i = from;
        while (i < end) {
            int parameterEnd = indexOf(header, ';', i, end);
            int nameStart = skipWhitespace(header, i, parameterEnd);
            char name = (nameStart < parameterEnd) ? header.charAt(nameStart) : 0;
            if (name == 'q' || name == 'Q') {
                int eq = skipWhitespace(header, nameStart + 1, parameterEnd);
                if (eq < parameterEnd && header.charAt(eq) == '=') {
                    return qualityValue(header, skipWhitespace(header, eq + 1, parameterEnd), parameterEnd);
                }
            }
            i = parameterEnd + 1;
        }
        return DEFAULT_QUALITY;
    }

    /**
     * Разбирает qvalue из участка {@code [from, end)} по грамматике RFC 9110:
     * {@code "0"["." 0*3DIGIT]} или {@code "1"["." 0*3("0")]}. Всё, что в неё не укладывается
     * ({@code "abc"}, {@code "1e-9"}, {@code ".5"}, {@code "9"}, восьмитысячезначное число),
     * даёт 0.0 — элемент не выбирается.
     * <p>
     * Разбор ручной, без {@code Double.parseDouble}: тот на невалидном значении бросает
     * {@code NumberFormatException}, а заполнение стектрейса стоит десятки микросекунд —
     * шестнадцать битых весов в 112-байтном заголовке обходились на порядок дороже, чем
     * весь разбор нормального. Исключение не должно быть способом разобрать чужой ввод.
     * <p>
     * Накопление {@code += digit * scale} расходится с {@code Double.parseDouble} в последнем
     * бите ({@code "0.3"} → 0.30000000000000004): для сравнения весов это безразлично —
     * порядок на всех 1001 легальном значении строгий, а разные написания одного веса
     * ({@code "0.5"} / {@code "0.50"}) дают один и тот же double. Менять на {@code parseDouble}
     * ради «точности» не нужно — вернётся стоимость исключений.
     */
    private static double qualityValue(String header, int from, int end) {
        int stop = end;
        while (stop > from && Character.isWhitespace(header.charAt(stop - 1))) {
            stop--;
        }
        int length = stop - from;
        if (length == 0 || length > MAX_QUALITY_LENGTH) {
            return 0.0;
        }
        char integerPart = header.charAt(from);
        if (integerPart != '0' && integerPart != '1') {
            return 0.0;
        }
        double quality = integerPart - '0';
        if (length == 1) {
            return quality;
        }
        if (header.charAt(from + 1) != '.') {
            return 0.0;
        }
        double scale = 0.1;
        for (int i = from + 2; i < stop; i++) {
            char digit = header.charAt(i);
            if (!isAsciiDigit(digit)) {
                return 0.0;
            }
            quality += (digit - '0') * scale;
            scale /= 10;
        }
        return quality > 1.0 ? 0.0 : quality;
    }

    /** Пуст ли участок {@code [from, end)} — целиком пробелы или нулевой длины. */
    private static boolean isBlank(String header, int from, int end) {
        return skipWhitespace(header, from, end) == end;
    }

    private static int skipWhitespace(String header, int from, int end) {
        int i = from;
        while (i < end && Character.isWhitespace(header.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** {@code String.indexOf} с верхней границей — чтобы не выходить за текущий элемент списка. */
    private static int indexOf(String header, char needle, int from, int end) {
        for (int i = from; i < end; i++) {
            if (header.charAt(i) == needle) {
                return i;
            }
        }
        return end;
    }
}
