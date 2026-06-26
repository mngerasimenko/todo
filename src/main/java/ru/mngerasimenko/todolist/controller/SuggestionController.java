package ru.mngerasimenko.todolist.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.DigestUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.mngerasimenko.todolist.dto.SuggestionBulkResponse;
import ru.mngerasimenko.todolist.dto.SuggestionResponse;
import ru.mngerasimenko.todolist.service.SuggestionService;
import ru.mngerasimenko.todolist.settings.SuggestionProperties;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Публичный эндпоинт глобального словаря подсказок (Server R-6).
 * <p>
 * Без JWT: гостевые клиенты тоже зовут для подсказок при вводе задач —
 * это устраняет «холодный старт» для нового пользователя.
 * Из {@code permitAll} в {@link ru.mngerasimenko.todolist.security.ApiSecurityConfig}.
 */
@RestController
@RequestMapping("/api/suggestions")
@RequiredArgsConstructor
@Tag(name = "Suggestions", description = "Глобальный словарь подсказок при вводе задачи")
@Validated
public class SuggestionController {

    private final SuggestionService suggestionService;
    private final SuggestionProperties properties;

    @GetMapping
    @Operation(summary = "Топ-N подсказок задач по prefix",
            description = "Возвращает наиболее частотные строки задач, начинающиеся с указанного префикса. " +
                    "Публичный (без JWT). При префиксе короче min-prefix-length возвращает пустой список.")
    public ResponseEntity<List<SuggestionResponse>> suggest(
            @Parameter(description = "Префикс задачи (строка, как ввёл пользователь)")
            @RequestParam(name = "prefix", required = false, defaultValue = "") String prefix,
            @Parameter(description = "Сколько подсказок вернуть (1..max-limit)")
            @RequestParam(name = "limit", required = false) @Min(1) @Max(50) Integer limit
    ) {
        int effectiveLimit = limit != null ? limit : properties.getDefaultLimit();
        return ResponseEntity.ok(suggestionService.suggest(prefix, effectiveLimit));
    }

    @GetMapping("/all")
    @Operation(summary = "Весь видимый словарь подсказок (bulk) для локального кэша клиента",
            description = "Отдаёт все строки словаря с freq >= min-freq и blocked=false для офлайн-кэша " +
                    "на устройстве (Server R-7): клиент кладёт их в локальную БД и матчит prefix без " +
                    "запроса к серверу на каждый символ. Публичный (без JWT). Поддерживает ETag/If-None-Match " +
                    "→ 304 Not Modified, т.к. клиент синкает словарь редко (~1 раз в сутки).")
    public ResponseEntity<List<SuggestionBulkResponse>> all(
            @Parameter(description = "ETag прошлого синка; при совпадении вернётся 304 без тела")
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) {
        List<SuggestionBulkResponse> all = suggestionService.findAllVisible();
        String etag = computeETag(all);
        // no-cache: разрешаем кэшировать, но обязываем ревалидацию через If-None-Match. Делает
        // поведение промежуточных кэшей (наш nginx) детерминированным — без него shared-кэш мог бы
        // отдать устаревшее тело по эвристическому TTL.
        CacheControl cacheControl = CacheControl.noCache();
        if (isNotModified(etag, ifNoneMatch)) {
            // Словарь не менялся с прошлого синка — тело не шлём (экономим трафик клиенту).
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .cacheControl(cacheControl)
                    .build();
        }
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(cacheControl)
                .body(all);
    }

    /**
     * Сравнение {@code If-None-Match} по слабой семантике RFC 7232 (так положено для GET).
     * Учитываем три реальности:
     * <ul>
     *   <li><b>{@code W/}-префикс:</b> наш nginx при gzip ослабляет strong-ETag до weak
     *       ({@code "h"} → {@code W/"h"}), и клиент вернёт именно {@code W/"h"} — голый
     *       {@code equals} тогда не сматчит и 304 никогда не сработает. Снимаем {@code W/} с обеих
     *       сторон. (Сами тоже отдаём weak-ETag — он семантически верен для контент-негоциируемого тела.)</li>
     *   <li><b>список через запятую:</b> {@code If-None-Match} может содержать несколько тегов;</li>
     *   <li><b>{@code *}:</b> матчит любую существующую репрезентацию.</li>
     * </ul>
     */
    private boolean isNotModified(String currentEtag, String ifNoneMatch) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        String current = stripWeakPrefix(currentEtag);
        for (String token : ifNoneMatch.split(",")) {
            String t = token.trim();
            if ("*".equals(t)) {
                return true;
            }
            if (stripWeakPrefix(t).equals(current)) {
                return true;
            }
        }
        return false;
    }

    private static String stripWeakPrefix(String etag) {
        return etag.startsWith("W/") ? etag.substring(2) : etag;
    }

    /**
     * Weak-ETag = хеш детерминированно упорядоченного содержимого словаря. Поля кодируем с
     * length-префиксом ({@code <len>:<text><len>:<textDisplay><freq>;}), чтобы разные наборы не
     * давали одинаковую строку (text/textDisplay могут содержать любой символ, включая разделители).
     * Порядок из {@code findAllVisible} стабилен ({@code freq DESC, text ASC}, text — PK) → при
     * неизменных данных ETag одинаков между вызовами (и между инстансами, разделяющими одну БД).
     * Возвращается уже в форме {@code W/"..."}; {@code ResponseEntity.eTag} такой валидный ETag не
     * оборачивает повторно.
     */
    private String computeETag(List<SuggestionBulkResponse> rows) {
        StringBuilder sb = new StringBuilder();
        for (SuggestionBulkResponse r : rows) {
            sb.append(r.getText().length()).append(':').append(r.getText())
                    .append(r.getTextDisplay().length()).append(':').append(r.getTextDisplay())
                    .append(r.getFreq()).append(';');
        }
        String hash = DigestUtils.md5DigestAsHex(sb.toString().getBytes(StandardCharsets.UTF_8));
        return "W/\"" + hash + "\"";
    }
}
