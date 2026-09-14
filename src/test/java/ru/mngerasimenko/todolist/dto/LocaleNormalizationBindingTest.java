package ru.mngerasimenko.todolist.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.mngerasimenko.todolist.dto.auth.RegisterRequest;
import ru.mngerasimenko.todolist.dto.push.RegisterPushTokenRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Все три точки приёма {@code locale} обязаны вести себя одинаково: длинный тег
 * от Android 13+ принимается и сводится к language[-REGION], мусор по-прежнему
 * отсекается bean validation.
 *
 * <p>Тест идёт по реальной цепочке «JSON → DTO → jakarta validation», а не по
 * setter'у напрямую: именно на этой цепочке боевой запрос получал 400 до контроллера.
 */
class LocaleNormalizationBindingTest {

    /** Тег из боевого лога: Android 13+ с кастомными региональными настройками, 35 символов. */
    private static final String ANDROID_13_TAG = "ru-RU-u-fw-mon-ms-metric-mu-celsius";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    private static <T> T bind(Class<T> type, String localeJson) throws Exception {
        return MAPPER.readValue(localeJson, type);
    }

    private static <T> boolean isValid(T request) {
        return validator.validate(request).isEmpty();
    }

    @Nested
    @DisplayName("RegisterRequest.locale")
    class Register {

        private String json(String locale) {
            return "{\"email\":\"a@b.ru\",\"name\":\"user\",\"password\":\"secret\",\"locale\":\"" + locale + "\"}";
        }

        @Test
        @DisplayName("боевой тег Android 13+ принимается и сводится к ru-RU")
        void android13Tag_Accepted() throws Exception {
            RegisterRequest request = bind(RegisterRequest.class, json(ANDROID_13_TAG));

            assertThat(request.getLocale()).isEqualTo("ru-RU");
            assertThat(isValid(request)).isTrue();
        }

        @Test
        @DisplayName("короткий тег старого клиента не меняется")
        void shortTag_Unchanged() throws Exception {
            RegisterRequest request = bind(RegisterRequest.class, json("en-US"));

            assertThat(request.getLocale()).isEqualTo("en-US");
            assertThat(isValid(request)).isTrue();
        }

        @Test
        @DisplayName("пустая строка остаётся пустой и остаётся валидной (fallback в контроллере)")
        void blank_StillValid() throws Exception {
            RegisterRequest request = bind(RegisterRequest.class, json(""));

            assertThat(request.getLocale()).isEmpty();
            assertThat(isValid(request)).isTrue();
        }

        @Test
        @DisplayName("поля нет — locale остаётся null и валиден")
        void absent_StillValid() throws Exception {
            RegisterRequest request = bind(RegisterRequest.class,
                    "{\"email\":\"a@b.ru\",\"name\":\"user\",\"password\":\"secret\"}");

            assertThat(request.getLocale()).isNull();
            assertThat(isValid(request)).isTrue();
        }

        @ParameterizedTest(name = "мусор \"{0}\" отклоняется")
        @ValueSource(strings = {"123", "*", "abcdefghij", "ru_RU"})
        void garbage_Rejected(String garbage) throws Exception {
            assertThat(isValid(bind(RegisterRequest.class, json(garbage)))).isFalse();
        }
    }

    @Nested
    @DisplayName("UpdateEmailLocaleRequest.locale")
    class UpdateEmailLocale {

        private String json(String locale) {
            return "{\"locale\":\"" + locale + "\"}";
        }

        @Test
        @DisplayName("боевой тег Android 13+ принимается и сводится к ru-RU")
        void android13Tag_Accepted() throws Exception {
            UpdateEmailLocaleRequest request = bind(UpdateEmailLocaleRequest.class, json(ANDROID_13_TAG));

            assertThat(request.getLocale()).isEqualTo("ru-RU");
            assertThat(isValid(request)).isTrue();
        }

        @Test
        @DisplayName("zh-Hans-CN сводится к zh-CN — script отбрасывается")
        void scriptSubtag_Dropped() throws Exception {
            UpdateEmailLocaleRequest request = bind(UpdateEmailLocaleRequest.class, json("zh-Hans-CN"));

            assertThat(request.getLocale()).isEqualTo("zh-CN");
            assertThat(isValid(request)).isTrue();
        }

        @Test
        @DisplayName("пустая строка по-прежнему невалидна (@NotBlank)")
        void blank_Rejected() throws Exception {
            assertThat(isValid(bind(UpdateEmailLocaleRequest.class, json("")))).isFalse();
        }

        @Test
        @DisplayName("тело в виде голой JSON-строки не должно обходить нормализующий setter")
        void bareJsonString_DoesNotBypassNormalization() {
            // У DTO с единственным полем Lombok-генерируемый конструктор Jackson может принять
            // за delegating creator и записать поле напрямую, минуя setLocale. Тогда клиент,
            // приславший строку вместо объекта, получил бы 400 на теге, который мы обязаны принять.
            String bareString = "\"" + ANDROID_13_TAG + "\"";

            assertThatThrownBy(() -> bind(UpdateEmailLocaleRequest.class, bareString))
                    .isInstanceOf(MismatchedInputException.class);
        }

        @ParameterizedTest(name = "мусор \"{0}\" отклоняется")
        @ValueSource(strings = {"123", "*", "abcdefghij", "ru_RU"})
        void garbage_Rejected(String garbage) throws Exception {
            assertThat(isValid(bind(UpdateEmailLocaleRequest.class, json(garbage)))).isFalse();
        }
    }

    @Nested
    @DisplayName("RegisterPushTokenRequest.locale")
    class RegisterPushToken {

        private String json(String locale) {
            return "{\"fcm_token\":\"tok\",\"device_id\":\"dev\",\"locale\":\"" + locale + "\"}";
        }

        @Test
        @DisplayName("боевой тег Android 13+ принимается и сводится к ru-RU")
        void android13Tag_Accepted() throws Exception {
            RegisterPushTokenRequest request = bind(RegisterPushTokenRequest.class, json(ANDROID_13_TAG));

            assertThat(request.getLocale()).isEqualTo("ru-RU");
            assertThat(isValid(request)).isTrue();
        }

        @Test
        @DisplayName("пустая строка остаётся валидной — старые клиенты шлют locale=\"\"")
        void blank_StillValid() throws Exception {
            RegisterPushTokenRequest request = bind(RegisterPushTokenRequest.class, json(""));

            assertThat(request.getLocale()).isEmpty();
            assertThat(isValid(request)).isTrue();
        }

        @ParameterizedTest(name = "мусор \"{0}\" отклоняется")
        @ValueSource(strings = {"123", "*", "abcdefghij", "ru_RU"})
        void garbage_Rejected(String garbage) throws Exception {
            assertThat(isValid(bind(RegisterPushTokenRequest.class, json(garbage)))).isFalse();
        }
    }
}
