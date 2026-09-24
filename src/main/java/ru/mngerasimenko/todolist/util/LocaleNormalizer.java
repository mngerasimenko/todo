package ru.mngerasimenko.todolist.util;

import java.util.Locale;

/**
 * Сводит присланный клиентом BCP-47 тег к короткому виду {@code language[-REGION]},
 * который влезает в {@code varchar(8)} колонок {@code todo_users.preferred_email_locale}
 * и {@code push_token.locale}.
 *
 * <p><b>Зачем.</b> Android отдаёт {@code Locale.getDefault().toLanguageTag()} целиком, а на
 * Android 13+ с кастомными региональными настройками тег раздувается Unicode-расширениями:
 * {@code ru-RU-u-fw-mon-ms-metric-mu-celsius} — 35 символов. Такой тег не влезал в
 * {@code @Size(max = 8)} и получал HTTP 400 в bean validation, то есть регистрация падала
 * ещё до контроллера. Сервер обязан принять то, что прислал клиент, и укоротить у себя —
 * иначе старые сборки приложения чинятся только апдейтом из магазина.
 *
 * <p><b>Что отбрасывается.</b> Всё, кроме языка и региона: Unicode-расширения ({@code -u-…}),
 * private use ({@code -x-…}), варианты и <i>script</i>. Script уходит вынужденно:
 * {@code zh-Hans-CN} — 10 символов, в колонку не влезает, а на выбор шаблона письма или
 * push-уведомления не влияет (ресурсы есть только для {@code ru} и {@code en}, остальное
 * сводится MessageSource к дефолту). Регион сохраняется по спеке наряда 276 — «принять то,
 * что прислал клиент, и хранить короткий тег (язык + регион)»; серверных потребителей
 * у региона сейчас нет (форматирования дат и чисел по локали в письмах нет — проверено),
 * он хранится как присланное клиентом значение.
 *
 * <p><b>Что НЕ делается.</b> Нераспознанный вход возвращается как есть — метод не решает,
 * годится ли значение. Решение остаётся за bean validation на поле DTO
 * ({@code LocaleValidation.PATTERN} + {@code @Size}), чтобы клиент получил осмысленное
 * «Locale must be a valid BCP-47 tag», а не молчаливую подмену на дефолт.
 * «Нераспознанный» здесь строго значит «язык не вычитывается вовсе»: у частично битого
 * тега {@code Locale.forLanguageTag} берёт well-formed префикс, поэтому {@code "ru-!"}
 * даёт {@code "ru"} и проходит. Это намеренно — язык клиент назвал, и терять его
 * из-за мусора в хвосте незачем; в колонку при этом уходит чистый тег, а не исходная строка.
 *
 * <p><b>Длину результата метод не гарантирует.</b> BCP-47 разрешает primary subtag до восьми
 * букв, а регион бывает трёхзначным числовым, так что {@code "abcdefgh-419"} остаётся
 * двенадцатью символами. В {@code varchar(8)} такое не попадает не из-за {@code @Size},
 * а из-за {@code LocaleValidation.PATTERN}: его {@code [a-zA-Z]{2,3}} режет язык длиннее трёх
 * букв. Кто будет расширять PATTERN под полный BCP-47 — обязан добавить сюда границу сам.
 */
public final class LocaleNormalizer {

    /**
     * Сколько символов тега разбирается.
     * <p>
     * Язык, script и регион всегда стоят в начале тега, а хвост из Unicode-расширений бывает
     * длинным: боевой тег Android 13+ — 35 символов, с календарём, системой мер и цифрами
     * ({@code ar-SA-u-ca-islamic-umalqura-fw-sat-ms-ussystem-mu-fahrenhe-nu-arab}) — 66. Поэтому
     * длинный тег не отклоняется, а разбирается только его начало: обрезанный на полуслове хвост
     * {@code Locale.forLanguageTag} отбрасывает как битый суффикс. Граница нужна, чтобы парсер
     * не видел мегабайтных строк: число суб-тегов BCP-47 не ограничено, и {@code "ru-" + "abcde-"×N} —
     * формально well-formed тег любой длины.
     */
    static final int MAX_PARSED_LENGTH = 64;

    /** Язык текстов для тега без языка — тот же, что {@code defaultLocale} в {@code I18nConfig}. */
    static final String DEFAULT_MESSAGE_LANGUAGE = "ru";

    private LocaleNormalizer() {
    }

    /**
     * @param languageTag тег как прислал клиент; {@code null} и пустая строка проходят насквозь
     * @return {@code language} или {@code language-REGION} в каноническом виде BCP-47
     *         ({@code "en-us"} → {@code "en-US"}, {@code "iw-IL"} → {@code "he-IL"}),
     *         либо исходная строка, если язык из её первых {@link #MAX_PARSED_LENGTH} символов
     *         не вычитывается
     */
    public static String normalize(String languageTag) {
        if (languageTag == null || languageTag.isBlank()) {
            return languageTag;
        }
        String head = languageTag.length() > MAX_PARSED_LENGTH
                ? languageTag.substring(0, MAX_PARSED_LENGTH)
                : languageTag;
        // forLanguageTag на мусоре не бросает, а возвращает Locale с пустым языком —
        // это и есть признак «не распознали».
        Locale parsed = Locale.forLanguageTag(head);
        String language = parsed.getLanguage();
        if (language.isEmpty()) {
            return languageTag;
        }
        String region = parsed.getCountry();
        return region.isEmpty() ? language : language + '-' + region;
    }

    /**
     * Локаль для резолва текста через {@code MessageSource} по тегу, сохранённому в БД.
     * <p>
     * Тег без языка — {@code und}, {@code und-RU} (их {@link #normalize} пропускает, а валидация
     * принимает), мусор, пустая строка, {@code null} — сводится к {@link #DEFAULT_MESSAGE_LANGUAGE}.
     * {@code Locale.forLanguageTag} дал бы для них {@code Locale.ROOT}, а у ROOT {@code MessageSource}
     * находит только пустой корневой {@code messages.properties} и до {@code defaultLocale} не доходит:
     * вместо текста клиенту уходит сам ключ. Тег с языком разбирается как есть — язык без своего
     * бандла {@code MessageSource} сам сводит к {@code defaultLocale}.
     */
    public static Locale toMessageLocale(String languageTag) {
        Locale parsed = languageTag == null ? Locale.ROOT : Locale.forLanguageTag(languageTag);
        return parsed.getLanguage().isEmpty() ? Locale.forLanguageTag(DEFAULT_MESSAGE_LANGUAGE) : parsed;
    }
}
