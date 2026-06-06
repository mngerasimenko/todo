package ru.mngerasimenko.todolist.dto.validation;

/**
 * Общие константы для валидации locale-полей в DTO (BCP-47).
 *
 * Принимаем минимально достаточный для наших целей подмножество BCP-47:
 * двух- или трёхбуквенный язык, опциональные суб-теги через дефис
 * (script, region, variant) — буквы и цифры. Примеры валидных значений:
 * {@code ru}, {@code en}, {@code pt-BR}, {@code zh-Hant-TW}.
 *
 * Не пытаемся валидировать значения вроде "ru-Cyrl-RU" против реального IANA
 * registry — задача — отсечь явный мусор ("*", "!@", "123", "  ") до того
 * как он попадёт в {@code User.preferredEmailLocale} и в БД. Дальше
 * {@code Locale.forLanguageTag} мягко fall-back'нет на дефолт при невалидном
 * BCP-47, поэтому строгая проверка не нужна.
 */
public final class LocaleValidation {

    public static final int MAX_LENGTH = 8;

    public static final String PATTERN = "^[a-zA-Z]{2,3}(-[a-zA-Z0-9]+)*$";

    /**
     * Pattern для опциональных locale-полей (например, RegisterPushTokenRequest.locale,
     * RegisterRequest.locale). Разрешает пустую строку дополнительно к валидному BCP-47:
     * старые Android-клиенты могут слать {@code locale=""} вместо отсутствующего поля,
     * сервис всё равно делает fallback на "ru" внутри. Без этого relaxed-варианта
     * @Pattern блокировал бы пустую строку с HTTP 400 и ломал регистрацию push-токена
     * у клиентов до апдейта.
     */
    public static final String PATTERN_OPTIONAL = "^$|^[a-zA-Z]{2,3}(-[a-zA-Z0-9]+)*$";

    public static final String PATTERN_MESSAGE = "Locale must be a valid BCP-47 tag";

    public static final String MAX_LENGTH_MESSAGE = "Locale must not exceed {max} characters";

    private LocaleValidation() {
    }
}
