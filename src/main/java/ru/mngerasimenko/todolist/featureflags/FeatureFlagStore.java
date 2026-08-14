package ru.mngerasimenko.todolist.featureflags;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory хранилище runtime-override'ов feature-флагов.
 *
 * Приоритет при {@link #isEnabled}:
 * 1. runtime override (установлен через {@code PUT /api/admin/flags})
 * 2. Spring Environment (env-переменные, application.properties)
 * 3. enum-default ({@link FeatureFlag#getDefaultValue()})
 *
 * Рестарт контейнера сбрасывает runtime-override'ы — это фича безопасности:
 * случайно выключенная защита не живёт дольше одного рестарта.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureFlagStore {

    private final Map<FeatureFlag, Boolean> runtimeOverrides = new ConcurrentHashMap<>();
    private final Environment environment;

    /**
     * Логирует на старте флаги, значения которых не удаётся разобрать.
     *
     * <p>{@code Environment.getProperty(name, Boolean.class)} не тотален: значение вроде
     * {@code app.suggestions.enabled=notabool} бросает {@code ConversionFailedException}, а
     * неразрешённый плейсхолдер — {@code PlaceholderResolutionException}.
     *
     * <p>Специально НЕ роняем приложение. Соблазн был: «пусть опечатка ловится на выкатке».
     * Но выкатка её не поймает — в {@code deploy.yml} health-check есть только у nginx, само
     * приложение поднимается без проверки, а {@code restart: unless-stopped} превратил бы отказ
     * старта в бесконечный рестарт-цикл с полностью недоступным API при зелёном деплое. Ошибка
     * в одном значении не должна ронять сервис: {@link #resolve} откатывается на дефолт, а
     * здесь мы один раз громко пишем в лог, чтобы это было видно.
     */
    @PostConstruct
    void logUnresolvableFlags() {
        for (FeatureFlag flag : FeatureFlag.values()) {
            try {
                environment.getProperty(flag.getName(), Boolean.class);
            } catch (RuntimeException e) {
                log.error("[flags] значение флага {} не разбирается ({}), используется дефолт {}",
                        flag.getName(), e.getMessage(), flag.getDefaultValue());
            }
        }
    }

    public boolean isEnabled(FeatureFlag flag) {
        return resolve(flag).value();
    }

    public void set(FeatureFlag flag, boolean value) {
        runtimeOverrides.put(flag, value);
    }

    /** Сбрасывает runtime-override. Если в env было значение — вернётся к нему; иначе к enum-default. */
    public void reset(FeatureFlag flag) {
        runtimeOverrides.remove(flag);
    }

    /**
     * Значения флагов, которые исполняет клиентское приложение — для {@code GET /api/status}.
     *
     * <p>Ключ — то же имя, что в URL админского пульта, значение — разрешённое по обычным
     * правилам приоритета. Серверные флаги сюда не попадают: их набор и описания — операционная
     * карта сервиса, и в каждое установленное приложение её отдавать незачем.
     *
     * <p>{@link LinkedHashMap} — чтобы порядок ключей в JSON был стабильным (порядок объявления
     * в enum): так ответ не «мерцает» между запросами и его удобно диффать при отладке.
     */
    public Map<String, Boolean> clientFlags() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (FeatureFlag flag : FeatureFlag.values()) {
            if (flag.isClientVisible()) {
                result.put(flag.getName(), isEnabled(flag));
            }
        }
        return result;
    }

    /** Снимок всех флагов с указанием источника значения — для GET /api/admin/flags. */
    public Map<FeatureFlag, Resolution> snapshot() {
        Map<FeatureFlag, Resolution> result = new EnumMap<>(FeatureFlag.class);
        for (FeatureFlag f : FeatureFlag.values()) {
            result.put(f, resolve(f));
        }
        return result;
    }

    private Resolution resolve(FeatureFlag flag) {
        Boolean runtime = runtimeOverrides.get(flag);
        if (runtime != null) {
            return new Resolution(runtime, FlagSource.RUNTIME);
        }
        Boolean envVal;
        try {
            envVal = environment.getProperty(flag.getName(), Boolean.class);
        } catch (RuntimeException e) {
            // Кривое значение в env — не повод отдавать 500. Клиентские флаги едут в публичный
            // /api/status, на котором висит splash приложения: исключение отсюда положило бы
            // запуск у всех установок. Молча берём дефолт (о проблеме уже сказано на старте,
            // см. logUnresolvableFlags).
            return new Resolution(flag.getDefaultValue(), FlagSource.DEFAULT);
        }
        if (envVal != null) {
            return new Resolution(envVal, FlagSource.ENV);
        }
        return new Resolution(flag.getDefaultValue(), FlagSource.DEFAULT);
    }

    /** Результат разрешения флага: текущее значение + откуда оно пришло. */
    public record Resolution(boolean value, FlagSource source) {
    }
}
