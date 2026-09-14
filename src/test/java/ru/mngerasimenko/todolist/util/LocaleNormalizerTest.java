package ru.mngerasimenko.todolist.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.mngerasimenko.todolist.dto.validation.LocaleValidation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Спека нормализации locale-тега (наряд 276): что прислал клиент → что уходит в БД.
 */
class LocaleNormalizerTest {

    @ParameterizedTest(name = "[{index}] \"{0}\" -> \"{1}\"")
    @CsvSource({
            // Короткий тег остаётся как прислан
            "ru,                                    ru",
            "en,                                    en",
            "ru-RU,                                 ru-RU",
            "en-US,                                 en-US",
            "pt-BR,                                 pt-BR",
            "fil-PH,                                fil-PH",
            "es-419,                                es-419",
            // Unicode-расширения Android 13+ срезаются — регрессия из боевого лога
            "ru-RU-u-fw-mon-ms-metric-mu-celsius,   ru-RU",
            "en-US-u-ca-gregory-nu-latn,            en-US",
            "ru-RU-x-private-use-subtag,            ru-RU",
            // Script отбрасывается: в колонку varchar(8) "zh-Hans-CN" не влезает
            "zh-Hans-CN,                            zh-CN",
            "zh-Hant-TW,                            zh-TW",
            "ru-Cyrl-RU,                            ru-RU",
            "zh-Hant,                               zh",
            // Каноническое написание BCP-47: язык в нижнем, регион в верхнем регистре
            "en-us,                                 en-US",
            "RU-ru,                                 ru-RU",
    })
    @DisplayName("распознанный тег сводится к language[-REGION]")
    void normalize_RecognizedTag_ReducedToLanguageAndRegion(String raw, String expected) {
        assertThat(LocaleNormalizer.normalize(raw)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" не трогаем")
    @ValueSource(strings = {"!@#", "123", "*", "-", "ru_RU", "abcdefghij", "  X"})
    @DisplayName("мусор возвращается как есть — решение о нём принимает bean validation")
    void normalize_UnrecognizedInput_ReturnedUnchanged(String raw) {
        assertThat(LocaleNormalizer.normalize(raw)).isEqualTo(raw);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> \"{1}\"")
    @CsvSource({
            "ru-!,          ru",
            "'ru-',         ru",
            "en-US-!!!,     en-US",
            "'ru-RU-',      ru-RU",
    })
    @DisplayName("битый хвост отбрасывается, если язык всё-таки вычитывается")
    void normalize_PartiallyMalformedTag_KeepsWellFormedPrefix(String raw, String expected) {
        assertThat(LocaleNormalizer.normalize(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("null и пустая строка проходят насквозь — fallback остаётся за вызывающим")
    void normalize_NullOrBlank_ReturnedUnchanged() {
        assertThat(LocaleNormalizer.normalize(null)).isNull();
        assertThat(LocaleNormalizer.normalize("")).isEmpty();
        assertThat(LocaleNormalizer.normalize("   ")).isEqualTo("   ");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {
            "ru-RU-u-fw-mon-ms-metric-mu-celsius", "zh-Hans-CN", "es-419", "en-us", "id-ID", "ru-!",
            "ar-SA-u-ca-islamic-umalqura-fw-sat-ms-ussystem-mu-fahrenhe-nu-arab", "i-default", "und-RU", "en-USA",
    })
    @DisplayName("нормализация идемпотентна — иначе значение из БД «поехало» бы на следующем PATCH")
    void normalize_IsIdempotent(String raw) {
        String once = LocaleNormalizer.normalize(raw);
        assertThat(LocaleNormalizer.normalize(once)).isEqualTo(once);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> \"{1}\"")
    @CsvSource({
            // Legacy-коды ISO 639 канонизируются самим JDK при разборе тега.
            // Пин на фактическое поведение: клиент присылает старый код, в БД лежит новый.
            "iw-IL,     he-IL",
            "in-ID,     id-ID",
            "ji,        yi",
            // Обратное направление регрессии не даёт
            "he-IL,     he-IL",
            "id-ID,     id-ID",
    })
    @DisplayName("legacy-коды ISO 639 сводятся к современным (JDK 17, useOldISOCodes=false)")
    void normalize_LegacyIsoCodes_CanonicalizedToModern(String raw, String expected) {
        assertThat(LocaleNormalizer.normalize(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("длинный тег не отклоняется: разбирается его начало, где стоят язык и регион")
    void normalize_TagLongerThanParsedPrefix_ReducedFromItsHead() {
        // Реальные теги Android с календарём, системой мер и цифрами бывают длиннее 64 символов.
        // Если возвращать такой вход как есть, @Size снова отвечает 400 — тот же баг, что чинит 276.
        String arabic = "ar-SA-u-ca-islamic-umalqura-fw-sat-ms-ussystem-mu-fahrenhe-nu-arab";
        String chinese = "zh-Hans-CN-u-ca-chinese-fw-mon-ms-ussystem-mu-fahrenhe-nu-hanidec";
        assertThat(arabic.length()).isGreaterThan(LocaleNormalizer.MAX_PARSED_LENGTH);
        assertThat(chinese.length()).isGreaterThan(LocaleNormalizer.MAX_PARSED_LENGTH);

        assertThat(LocaleNormalizer.normalize(arabic)).isEqualTo("ar-SA");
        assertThat(LocaleNormalizer.normalize(chinese)).isEqualTo("zh-CN");
        assertThat(LocaleNormalizer.normalize("ru-RU-u-fw-mon-ms-metric-mu-celsius")).isEqualTo("ru-RU");
    }

    @Test
    @DisplayName("гигантский вход: well-formed тег сводится к языку, мусор без языка возвращается как есть")
    void normalize_HugeInput_ReducedToLanguageOrReturnedUnchanged() {
        // Число суб-тегов BCP-47 не ограничено: "ru-" + десять тысяч вариантов — формально
        // well-formed тег. По результату разбор префикса не отличить от разбора целиком, так что
        // обрезку до MAX_PARSED_LENGTH этот тест не охраняет — она снимает только нагрузку на парсер.
        String hostile = "ru-" + "abcde-".repeat(10_000) + "abcde";
        String garbage = "!".repeat(100_000);

        assertThat(LocaleNormalizer.normalize(hostile)).isEqualTo("ru");
        assertThat(LocaleNormalizer.normalize(garbage)).isSameAs(garbage);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" не трогаем")
    @ValueSource(strings = {"und", "und-RU"})
    @DisplayName("und — «язык не определён»: возвращается как есть и, как и на master, проходит валидацию")
    void normalize_UndeterminedLanguage_ReturnedUnchanged(String raw) {
        // Locale.forLanguageTag отдаёт для und пустой язык, и нормализатор считает вход нераспознанным.
        // PATTERN такой тег пропускает: в колонку ляжет und, письма уйдут на языке по умолчанию.
        assertThat(LocaleNormalizer.normalize(raw)).isEqualTo(raw);
        assertThat(raw).matches(LocaleValidation.PATTERN);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> \"{1}\"")
    @CsvSource({
            // Grandfathered-теги JDK заменяет современными кодами
            "i-default,     en",
            "i-klingon,     tlh",
            "zh-min-nan,    nan",
            // Трёхбуквенный сабтег сразу после языка — extlang, и он заменяет язык.
            // ISO3-регион ("en-USA") клиенты не шлют; пин на фактическое поведение JDK
            "zh-yue-HK,     yue-HK",
            "en-USA,        usa",
    })
    @DisplayName("grandfathered и extlang сводятся так, как их разбирает JDK 17")
    void normalize_GrandfatheredAndExtlang_FollowJdk(String raw, String expected) {
        assertThat(LocaleNormalizer.normalize(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("нормализатор НЕ гарантирует влезание в MAX_LENGTH — границу держит @Pattern")
    void normalize_LongPrimarySubtag_ExceedsMaxLength() {
        // BCP-47 разрешает primary subtag до 8 букв, а регион бывает 3-значным числовым.
        // Такое значение нормализатор пропускает целиком — в колонку varchar(8) оно не влезло бы,
        // и отсекает его @Pattern (^[a-zA-Z]{2,3}...), а не @Size. Комментарий, который
        // обещает обратное, опаснее отсутствующего.
        String normalized = LocaleNormalizer.normalize("abcdefgh-419");

        assertThat(normalized).isEqualTo("abcdefgh-419");
        assertThat(normalized.length()).isGreaterThan(LocaleValidation.MAX_LENGTH);
        assertThat(normalized).doesNotMatch(LocaleValidation.PATTERN);
    }
}
