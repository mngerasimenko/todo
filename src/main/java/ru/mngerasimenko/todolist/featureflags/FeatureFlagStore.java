package ru.mngerasimenko.todolist.featureflags;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import ru.mngerasimenko.todolist.model.FeatureFlagOverride;
import ru.mngerasimenko.todolist.repository.FeatureFlagOverrideRepository;

import java.time.LocalDateTime;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Хранилище runtime-override'ов feature-флагов.
 *
 * Приоритет при {@link #isEnabled}:
 * 1. runtime override (установлен через {@code PUT /api/admin/flags})
 * 2. Spring Environment (env-переменные, application.properties)
 * 3. enum-default ({@link FeatureFlag#getDefaultValue()})
 *
 * <p>Значение всегда читается из памяти, поэтому {@code isEnabled} можно звать хоть на каждый
 * запрос. БД участвует только в двух местах: один раз при старте (загрузка) и при каждом
 * переключении через админский пульт.
 *
 * <p>Сколько живёт переключение — свойство самого флага ({@link OverrideLifetime}):
 * <ul>
 *   <li>{@code PROCESS} — до ближайшего рестарта. Так ведёт себя защита ({@code rate-limit}):
 *       снятая на время разбирательства, она обязана восстановиться сама.</li>
 *   <li>{@code PERSISTENT} — переживает рестарт, деплой и пересоздание контейнеров. Для флагов
 *       фич процессное поведение было вредным: аварийно выключенная функция возвращалась
 *       пользователям сама, причём в непредсказуемый момент — staging передеплоивается на
 *       каждый merge в master.</li>
 * </ul>
 *
 * <p>Почему БД, а не Redis: у Redis здесь {@code maxmemory-policy allkeys-lru} (ключ, который
 * давно не читали, может быть вытеснен) и RDB-снапшоты по расписанию (при одном изменении —
 * раз в час). Для аварийного выключателя «обычно переживает» не годится.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureFlagStore {

    private final Map<FeatureFlag, Boolean> runtimeOverrides = new ConcurrentHashMap<>();
    /** Флаги, чей override лежит в БД, а не только в памяти. Нужен, чтобы отличать в пульте
     *  «переключено 5 минут назад в этом процессе» от «строка живёт с прошлого месяца и
     *  переживает деплой» — во время инцидента это первое, что нужно понять. */
    private final Set<FeatureFlag> persistedFlags = ConcurrentHashMap.newKeySet();
    private final Environment environment;
    private final FeatureFlagOverrideRepository overrideRepository;

    /**
     * Поднимает сохранённые переключения в память при старте.
     *
     * <p>Ошибку БД глушим: без флагов сервис работает (на env и дефолтах), а вот падение старта
     * из-за них означало бы рестарт-цикл с недоступным API — см. соседний
     * {@link #logUnresolvableFlags}. Строки для флагов, которых уже нет в реестре или которые
     * стали {@code PROCESS}, игнорируем: реестр — источник истины, БД лишь помнит значения.
     */
    @PostConstruct
    void loadPersistedOverrides() {
        try {
            for (FeatureFlagOverride row : overrideRepository.findAll()) {
                FeatureFlag flag = FeatureFlag.findByName(row.getName()).orElse(null);
                if (flag == null) {
                    log.warn("[flags] в БД лежит override неизвестного флага {} — игнорирую", row.getName());
                    continue;
                }
                if (!flag.isOverridePersistent()) {
                    log.warn("[flags] флаг {} больше не PERSISTENT — сохранённый override игнорирую",
                            flag.getName());
                    // Строку НЕ удаляем: реклассификация обратима (откат деплоя вернёт флаг в
                    // PERSISTENT), а удаление — нет. Игнорирования достаточно, чтобы значение не
                    // действовало; так же поступаем со строками флагов, вовсе исчезнувших из
                    // реестра. ЗДЕСЬ ЕДИНСТВЕННОЕ МЕСТО, отвечающее за такие строки: ни set(),
                    // ни reset() их не трогают. Цена — при цепочке PERSISTENT → PROCESS →
                    // PERSISTENT старое значение оживёт; убирать в таком случае SQL-запросом.
                    continue;
                }
                runtimeOverrides.put(flag, row.isEnabled());
                log.info("[flags] восстановлен override {}={}", flag.getName(), row.isEnabled());
            }
        } catch (RuntimeException e) {
            // RuntimeException, а не DataAccessException: TransactionException — СИБЛИНГ
            // DataAccessException, а не наследник, и JpaTransactionManager заворачивает в него
            // отказ выдать EntityManager. Такое исключение из @PostConstruct валит старт, то есть
            // даёт ровно тот рестарт-цикл с недоступным API, которого мы здесь избегаем.
            log.error("[flags] не удалось прочитать сохранённые override'ы ({}), работаем на env/дефолтах",
                    e.getMessage());
        }
    }

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

    /**
     * Выставляет ручное значение флага. Для {@code PERSISTENT}-флагов ещё и сохраняет его,
     * чтобы переключение пережило рестарт и деплой.
     *
     * <p>Память обновляется ПЕРВОЙ и независимо от БД: команда админа должна подействовать
     * немедленно, даже если сохранить её не вышло. Сбой записи логируем — тогда переключение
     * действует как раньше, до ближайшего рестарта, и это лучше, чем не подействовать вовсе.
     */
    public boolean set(FeatureFlag flag, boolean value, String actor) {
        runtimeOverrides.put(flag, value);
        if (!flag.isOverridePersistent()) {
            // В БД не ходим вовсе. Флаги защиты выключают как раз тогда, когда сервису плохо —
            // нередко из-за самой БД, — и лишний round-trip повесил бы ответ на connection-timeout
            // (30 с), пока переключение уже действует в памяти. Строка от прежней классификации,
            // если она осталась, просто игнорируется при загрузке (см. loadPersistedOverrides).
            return false;
        }
        try {
            overrideRepository.save(new FeatureFlagOverride(
                    flag.getName(), value, LocalDateTime.now(), actor));
            persistedFlags.add(flag);
            return true;
        } catch (RuntimeException e) {
            // Снимаем маркер обязательно: если строка для флага уже была, в БД осталось СТАРОЕ
            // значение, и пульт, продолжая показывать PERSISTED, обещал бы пережить рестарт
            // ровно противоположное тому, что после него восстановится.
            persistedFlags.remove(flag);
            log.error("[flags] override {}={} применён, но НЕ сохранён ({}) — слетит на рестарте",
                    flag.getName(), value, e.getMessage());
            return false;
        }
    }

    /** Сбрасывает runtime-override. Если в env было значение — вернётся к нему; иначе к enum-default. */
    public boolean reset(FeatureFlag flag) {
        runtimeOverrides.remove(flag);
        persistedFlags.remove(flag);
        if (!flag.isOverridePersistent()) {
            // Как и в set(): для флагов защиты в БД не ходим вовсе. Возвращать защиту приходится
            // ровно тогда, когда сервису плохо, и ждать connection-timeout здесь нечего — строки
            // для таких флагов не пишутся, а оставшаяся от прежней классификации всё равно
            // игнорируется при загрузке.
            return true;
        }
        try {
            overrideRepository.deleteById(flag.getName());
            return true;
        } catch (RuntimeException e) {
            // Не удалили — значит на следующем старте override вернётся. Это заметнее, чем тихо
            // разъехавшиеся память и БД, поэтому пишем ERROR, а не warn.
            log.error("[flags] override {} снят в памяти, но НЕ удалён из БД ({}) — вернётся после рестарта",
                    flag.getName(), e.getMessage());
            return false;
        }
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
            return new Resolution(runtime,
                    persistedFlags.contains(flag) ? FlagSource.PERSISTED : FlagSource.RUNTIME);
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
