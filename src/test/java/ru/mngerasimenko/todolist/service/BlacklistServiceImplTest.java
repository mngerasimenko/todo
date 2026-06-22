package ru.mngerasimenko.todolist.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты {@link BlacklistServiceImpl}. Используют реальный
 * {@code suggestion_blacklist.txt} из classpath: проверяют что де-обфускация и substring
 * detection ловят базовые варианты обхода.
 */
class BlacklistServiceImplTest {

    private BlacklistServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BlacklistServiceImpl();
        service.loadBlacklist();
    }

    // ===== Negative (нормальные продукты не блокируются) =====

    @Test
    void contains_NormalProducts_ReturnsFalse() {
        assertThat(service.contains("молоко")).isFalse();
        assertThat(service.contains("хлеб")).isFalse();
        assertThat(service.contains("apples")).isFalse();
        assertThat(service.contains("Купить картошку")).isFalse();
    }

    @Test
    void contains_GroceryWordsCollidingWithShortRoots_ReturnsFalse() {
        // Регресс panel-review iter3: голые 3-буквенные корни (сук/хер/манда) убраны,
        // т.к. substring-проверка глушила обычные продукты. Эти строки — НЕ мат.
        assertThat(service.contains("мандарин")).isFalse();
        assertThat(service.contains("мандарины")).isFalse();
        assertThat(service.contains("херес")).isFalse();
        assertThat(service.contains("сукно")).isFalse();
    }

    @Test
    void contains_SpecificVulgarFormsStillBlocked_ReturnsTrue() {
        // Удаление коротких корней НЕ должно ослабить специфичные формы.
        assertThat(service.contains("сука")).isTrue();
        assertThat(service.contains("сучка")).isTrue();
        assertThat(service.contains("херня")).isTrue();
        assertThat(service.contains("херов")).isTrue();
    }

    @Test
    void contains_NullOrBlank_ReturnsFalse() {
        assertThat(service.contains(null)).isFalse();
        assertThat(service.contains("")).isFalse();
        assertThat(service.contains("   ")).isFalse();
    }

    @Test
    void contains_AdultEscortTerms_ReturnsTrue() {
        // Найдено при анализе прод-словаря 2026-06-22 — дыра в фильтре закрыта.
        assertThat(service.contains("Видео миньета на фоне")).isTrue();
        assertThat(service.contains("эскорт услуги")).isTrue();
        assertThat(service.contains("проститутка")).isTrue();
        assertThat(service.contains("порно")).isTrue();
        assertThat(service.contains("эротическое бельё")).isTrue();
    }

    @Test
    void contains_AdultRootsNoCollision_ReturnsFalse() {
        // «проститу» не ловит «простите»; «анал»/«интим» намеренно НЕ в списке →
        // «анализы» и «интимная гигиена» проходят.
        assertThat(service.contains("простите за опоздание")).isFalse();
        assertThat(service.contains("сдать анализы")).isFalse();
        assertThat(service.contains("интимная гигиена")).isFalse();
    }

    // ===== Positive (мат и его обфускации блокируются) =====

    @Test
    void contains_BareRussianRoot_ReturnsTrue() {
        assertThat(service.contains("сука")).isTrue();
        assertThat(service.contains("хуй")).isTrue();
    }

    @Test
    void contains_CaseInsensitive() {
        assertThat(service.contains("СУКА")).isTrue();
        assertThat(service.contains("СуКа")).isTrue();
    }

    @Test
    void contains_LatinSubstitution_ReturnsTrue() {
        // 'с'→'c', 'у'→'y', 'к'→'k', 'а'→'a' даёт "cyka" — должен мапиться обратно.
        assertThat(service.contains("cyka")).isTrue();
    }

    @Test
    void contains_DigitObfuscation_ReturnsTrue() {
        // '0'→'о', '1'→'и' — «х0й» = «хой»; «cyk@» = «сука».
        assertThat(service.contains("cyk@")).isTrue();
    }

    @Test
    void contains_PunctuationStuffing_ReturnsTrue() {
        // знаки препинания должны выбрасываться нормализатором
        assertThat(service.contains("с_у_к_а")).isTrue();
        assertThat(service.contains("с.у.к.а")).isTrue();
        assertThat(service.contains("с*у*к*а")).isTrue();
    }

    @Test
    void contains_EnglishProfanity_ReturnsTrue() {
        assertThat(service.contains("fuck off")).isTrue();
        assertThat(service.contains("Shitlist")).isTrue();
    }

    @Test
    void contains_SubstringInLongerWord_ReturnsTrue() {
        // «нахуй» — корень из blacklist'а
        assertThat(service.contains("идинахуй")).isTrue();
    }
}
