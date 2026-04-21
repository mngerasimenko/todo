package ru.mngerasimenko.todolist.featureflags;

import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
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
@Service
@RequiredArgsConstructor
public class FeatureFlagStore {

    private final Map<FeatureFlag, Boolean> runtimeOverrides = new ConcurrentHashMap<>();
    private final Environment environment;

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
        Boolean envVal = environment.getProperty(flag.getName(), Boolean.class);
        if (envVal != null) {
            return new Resolution(envVal, FlagSource.ENV);
        }
        return new Resolution(flag.getDefaultValue(), FlagSource.DEFAULT);
    }

    /** Результат разрешения флага: текущее значение + откуда оно пришло. */
    public record Resolution(boolean value, FlagSource source) {
    }
}
