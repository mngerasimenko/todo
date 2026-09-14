package ru.mngerasimenko.todolist.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import ru.mngerasimenko.todolist.dto.validation.LocaleValidation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты разбора {@code Accept-Language}.
 *
 * Заголовок приходит от неаутентифицированного клиента, поэтому проверяется не только
 * happy path, но и то, что мусор не бросает исключений (JDK-разбор бросал на "-"
 * ArrayIndexOutOfBoundsException, превращая ответ публичного эндпоинта в HTTP 500)
 * и что разбор конечен на 8-килобайтном заголовке.
 */
class AcceptLanguageParserTest {

    private static final Set<String> SUPPORTED = Set.of("ru", "en");

    // === bestLanguageTag: happy path ===

    @Test
    void bestLanguageTag_SingleTag_ReturnsItLowercased() {
        assertThat(AcceptLanguageParser.bestLanguageTag("en-US")).isEqualTo("en-us");
    }

    @Test
    void bestLanguageTag_WithoutQualities_ReturnsFirstTag() {
        assertThat(AcceptLanguageParser.bestLanguageTag("en-US,en;q=0.9,ru;q=0.8")).isEqualTo("en-us");
    }

    @Test
    void bestLanguageTag_PicksHighestQuality() {
        assertThat(AcceptLanguageParser.bestLanguageTag("ru;q=0.1,en;q=0.9")).isEqualTo("en");
        assertThat(AcceptLanguageParser.bestLanguageTag("ru;q=0.9,en;q=0.1")).isEqualTo("ru");
        // Элемент без q весит 1.0 и обыгрывает явно ослабленные соседние.
        assertThat(AcceptLanguageParser.bestLanguageTag("ru;q=0.9,en")).isEqualTo("en");
    }

    @Test
    void bestLanguageTag_EqualQualities_KeepsFirstMentioned() {
        assertThat(AcceptLanguageParser.bestLanguageTag("ru;q=0.5,en;q=0.5")).isEqualTo("ru");
    }

    @Test
    void bestLanguageTag_ZeroQualityMeansUnacceptable() {
        // RFC 9110: q=0 — «неприемлемо», такой язык не выбирается даже как единственный.
        assertThat(AcceptLanguageParser.bestLanguageTag("en;q=0")).isNull();
        assertThat(AcceptLanguageParser.bestLanguageTag("en;q=0,ru;q=0.1")).isEqualTo("ru");
    }

    @Test
    void bestLanguageTag_ToleratesWhitespaceAroundElements() {
        assertThat(AcceptLanguageParser.bestLanguageTag("  ru ;  q = 0.3 ,  en ; q=0.7 ")).isEqualTo("en");
    }

    @Test
    void bestLanguageTag_MultiSubtagTag_KeptWhole() {
        assertThat(AcceptLanguageParser.bestLanguageTag("zh-Hant-TW")).isEqualTo("zh-hant-tw");
    }

    @Test
    void bestLanguageTag_OverlongTag_IsRejected() {
        // Число суб-тегов в BCP-47 ничем не ограничено, поэтому клиент может прислать «тег»
        // в тысячи символов. Это не язык, а мусор: он не должен доезжать до вызывающего,
        // который пишет результат в узкую колонку БД.
        String overlong = "ru" + "-abcdefgh".repeat(880);
        assertThat(overlong).hasSizeGreaterThan(7000);
        assertThat(AcceptLanguageParser.bestLanguageTag(overlong)).isNull();
        assertThat(AcceptLanguageParser.bestLanguageTag("ru-Cyrl-RU-x-private-use-subtags-and-more")).isNull();
    }

    @Test
    void bestLanguageTag_AnyResult_SatisfiesLocaleValidationPrimarySubtag() {
        // Контракт класса: primary subtag разобранного тега — то же подмножество, что принимает
        // LocaleValidation.PATTERN, иначе значение не пройдёт валидацию у вызывающего.
        String[] headers = {
                "en-US,en;q=0.9,ru;q=0.8", "zh-Hant-TW", "es-419", "-,en-GB", "ru", "PT-br",
                "en;q=0.5,de-DE-1901;q=0.9", "x-klingon,tlh", "i-navajo,nv", "*,ja", "sr-Latn-RS"
        };
        for (String header : headers) {
            String tag = AcceptLanguageParser.bestLanguageTag(header);
            assertThat(tag).as("header %s must yield a language", header).isNotNull();
            assertThat(tag).as("tag from header %s", header).matches(LocaleValidation.PATTERN);
            assertThat(AcceptLanguageParser.primarySubtagOf(tag))
                    .as("primary subtag of %s (header %s)", tag, header)
                    .matches("[a-z]{2,3}");
        }
    }

    // === bestLanguageTag: мусор ===

    @ParameterizedTest
    @ValueSource(strings = {
            "-",            // ill-formed range: именно на нём JDK бросал AIOOBE
            "  -  ",
            "-;q=0.5",
            "--",
            "en-",          // пустой суб-тег
            "-en",
            "e",            // primary subtag короче двух букв
            "engl",         // primary subtag длиннее трёх букв
            "1234",
            "*",            // wildcard как конкретная локаль бессмыслен
            "*;q=0.8",
            "!@#",
            "en_US",        // подчёркивание вместо дефиса — не BCP-47
            "ru-Cyrl-verylongsubtag",
            ",,,",
            ";;;",
            "   ",
            ""
    })
    void bestLanguageTag_MalformedInput_ReturnsNull(String header) {
        assertThat(AcceptLanguageParser.bestLanguageTag(header)).isNull();
    }

    @Test
    void bestLanguageTag_NullHeader_ReturnsNull() {
        assertThat(AcceptLanguageParser.bestLanguageTag(null)).isNull();
    }

    @Test
    void bestLanguageTag_MalformedElementAmongValidOnes_IsSkipped() {
        assertThat(AcceptLanguageParser.bestLanguageTag("-,en-GB")).isEqualTo("en-gb");
        assertThat(AcceptLanguageParser.bestLanguageTag("en-GB,-")).isEqualTo("en-gb");
        assertThat(AcceptLanguageParser.bestLanguageTag("*,ru")).isEqualTo("ru");
        assertThat(AcceptLanguageParser.bestLanguageTag("-;q=1.0,ru;q=0.2")).isEqualTo("ru");
    }

    @Test
    void bestLanguageTag_MalformedQuality_TreatsElementAsUnacceptable() {
        assertThat(AcceptLanguageParser.bestLanguageTag("en;q=abc")).isNull();
        assertThat(AcceptLanguageParser.bestLanguageTag("en;q=")).isNull();
        // Вне диапазона RFC 9110 [0, 1] — тоже не выбираем, но разбор не роняем.
        assertThat(AcceptLanguageParser.bestLanguageTag("en;q=9")).isNull();
        assertThat(AcceptLanguageParser.bestLanguageTag("en;q=abc,ru;q=0.1")).isEqualTo("ru");
    }

    @Test
    void bestLanguageTag_UnknownParameterIsNotMistakenForQuality() {
        // Вес подменяет только параметр с именем ровно "q" — не любой, в котором есть буква q
        // и знак равенства. Иначе элемент молча получал чужой вес и проигрывал соседям.
        assertThat(AcceptLanguageParser.bestLanguageTag("en;level=1,ru;q=0.5")).isEqualTo("en");
        assertThat(AcceptLanguageParser.bestLanguageTag("en;seq=0.1,ru;q=0.2")).isEqualTo("en");
        assertThat(AcceptLanguageParser.bestLanguageTag("en;uniq=0.1,ru;q=0.2")).isEqualTo("en");
        assertThat(AcceptLanguageParser.bestLanguageTag("en;foo=q=0,ru;q=0.5")).isEqualTo("en");
        // ...но настоящий q находится и вторым параметром, и в верхнем регистре.
        assertThat(AcceptLanguageParser.bestLanguageTag("en;level=1;q=0.2,ru;q=0.5")).isEqualTo("ru");
        assertThat(AcceptLanguageParser.bestLanguageTag("ru;Q=0.1,en;Q=0.9")).isEqualTo("en");
    }

    @Test
    void bestLanguageTag_QualityOutsideRfcSyntax_TreatsElementAsUnacceptable() {
        // qvalue по RFC 9110 — "0[.ddd]" или "1[.000]", не длиннее пяти символов.
        // Всё остальное — не «почти число», а мусор, и элемент с ним не выбирается.
        // Этот же тест — детерминированный сторож разбора без Double.parseDouble: под
        // parseDouble-реализацией "1e-9", ".5" и "00.5" разбирались как числа и давали "en".
        assertThat(AcceptLanguageParser.bestLanguageTag("en;q=1e-9")).isNull();
        assertThat(AcceptLanguageParser.bestLanguageTag("en;q=.5")).isNull();
        assertThat(AcceptLanguageParser.bestLanguageTag("en;q=00.5")).isNull();
        assertThat(AcceptLanguageParser.bestLanguageTag("en;q=0.5555")).isNull();
        assertThat(AcceptLanguageParser.bestLanguageTag("en;q=" + "9".repeat(8000))).isNull();
        // Валидные формы разбираются точно.
        assertThat(AcceptLanguageParser.bestLanguageTag("ru;q=0.001,en;q=0.002")).isEqualTo("en");
        assertThat(AcceptLanguageParser.bestLanguageTag("ru;q=1.000,en;q=0.999")).isEqualTo("ru");
    }

    @Test
    void bestLanguageTag_EmptyElementsDoNotConsumeTheBudget() {
        // Пустой элемент — не language range: 17-байтный заголовок из одних запятых
        // не должен съедать лимит и глушить язык, который идёт следом.
        assertThat(AcceptLanguageParser.bestLanguageTag(",".repeat(64) + "en")).isEqualTo("en");
        assertThat(AcceptLanguageParser.bestLanguageTag("  ,  ,  ,  ,  ,  ,  ,  ,  ,  ,"
                + "  ,  ,  ,  ,  ,  ,  ,  ,  ,ru")).isEqualTo("ru");
    }

    @Test
    void bestLanguageTag_HugeValidHeader_ReadsOnlyTheHead() {
        StringBuilder header = new StringBuilder("ru;q=0.4,");
        for (int i = 0; header.length() < 8000; i++) {
            header.append("qa").append((char) ('a' + i % 26)).append("-x").append(i).append(";q=0.5,");
        }
        header.append("en;q=0.9");

        // Первый элемент весит 0.4, следующие — 0.5, а "en;q=0.9" лежит далеко за лимитом
        // и не рассматривается: побеждает первый из разобранных элементов с весом 0.5.
        assertThat(AcceptLanguageParser.bestLanguageTag(header.toString())).isEqualTo("qaa-x0");
    }

    // === bestSupportedLanguage ===

    @Test
    void bestSupportedLanguage_MatchesRegionalTagByPrimarySubtag() {
        assertThat(AcceptLanguageParser.bestSupportedLanguage("en-GB", SUPPORTED, "ru")).isEqualTo("en");
    }

    @Test
    void bestSupportedLanguage_IgnoresUnsupportedLanguagesEvenIfPreferred() {
        assertThat(AcceptLanguageParser.bestSupportedLanguage("fr;q=1.0,en;q=0.2", SUPPORTED, "ru"))
                .isEqualTo("en");
    }

    @Test
    void bestSupportedLanguage_NothingAcceptable_ReturnsFallback() {
        assertThat(AcceptLanguageParser.bestSupportedLanguage("fr,de", SUPPORTED, "ru")).isEqualTo("ru");
        assertThat(AcceptLanguageParser.bestSupportedLanguage("en;q=0", SUPPORTED, "ru")).isEqualTo("ru");
        assertThat(AcceptLanguageParser.bestSupportedLanguage("-", SUPPORTED, "ru")).isEqualTo("ru");
        assertThat(AcceptLanguageParser.bestSupportedLanguage(null, SUPPORTED, "ru")).isEqualTo("ru");
    }

    // === primarySubtagOf ===

    @Test
    void primarySubtagOf_CutsEverythingAfterFirstDash() {
        assertThat(AcceptLanguageParser.primarySubtagOf("zh-hant-tw")).isEqualTo("zh");
        assertThat(AcceptLanguageParser.primarySubtagOf("ru")).isEqualTo("ru");
        assertThat(AcceptLanguageParser.primarySubtagOf("")).isEmpty();
    }

    @Test
    void primarySubtagOf_NullTag_ReturnsNullInsteadOfThrowing() {
        // Класс обещает не бросать вовсе — включая метод, которому скормили результат
        // предыдущего вызова, не проверив его на null.
        assertThat(AcceptLanguageParser.primarySubtagOf(null)).isNull();
    }
}
