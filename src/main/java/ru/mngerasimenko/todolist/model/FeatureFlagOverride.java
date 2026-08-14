package ru.mngerasimenko.todolist.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Долговечный runtime-override feature-флага, выставленный через
 * {@code PUT /api/admin/flags/{name}/{value}}.
 *
 * <p>Строка есть — значит для флага действует ручное значение; строки нет — значение берётся
 * из env или из дефолта в enum. Хранится в БД, а не в памяти процесса, потому что аварийно
 * выключенная функция не должна возвращаться пользователям сама: staging передеплоивается на
 * каждый merge в master, а прод — на каждую ручную выкатку.
 *
 * <p>Пишутся сюда только флаги с {@code OverrideLifetime.PERSISTENT}. Защитные (rate-limit)
 * намеренно остаются процессными — случайно снятая защита обязана восстановиться сама.
 *
 * <p>Читается один раз при старте, дальше живёт в памяти {@code FeatureFlagStore}, поэтому
 * на горячий путь запросов эта таблица не попадает.
 *
 * <p><b>Без {@code @Version} — намеренно</b>, в отличие от остальных сущностей проекта. С
 * непримитивным version-полем {@code SimpleJpaRepository.save()} считает объект новым (его
 * {@code isNew()} возвращает true, пока version равен null) и делает {@code persist()} вместо
 * {@code merge()}: второе переключение того же флага упало бы на duplicate key, исключение
 * поглотил бы catch в сторе, и долговечность молча перестала бы работать. Конкурентная запись
 * тут не проблема — переключает только супер-админ.
 */
@Entity
@Table(name = "feature_flag_override")
public class FeatureFlagOverride {

    /** Имя флага — то же, что в URL админского пульта и в реестре {@code FeatureFlag}. */
    @Id
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** Значение, которое действует вместо env/дефолта. */
    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /** Когда переключили — чтобы при разборе инцидента было видно, что и когда меняли. */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Email админа, выполнившего переключение. Может быть null для значений, проставленных
     *  не через API (например, вручную в БД при недоступном приложении). */
    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    public FeatureFlagOverride() {
    }

    public FeatureFlagOverride(String name, boolean enabled, LocalDateTime updatedAt, String updatedBy) {
        this.name = name;
        this.enabled = enabled;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }
}
