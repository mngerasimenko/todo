package ru.mngerasimenko.todolist.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * JPA-сущность глобального словаря подсказок (Server R-6).
 * <p>
 * Каждая запись — нормализованная (lower + trim) строка задачи, частота её использования
 * и время последнего использования. PK — сам нормализованный текст: естественный
 * dedup без отдельного id и автоинкремента.
 * <p>
 * Чтение через {@code SELECT … WHERE text LIKE :prefix || '%'} c индексом
 * {@code idx_task_suggestion_prefix} (varchar_pattern_ops). Запись — через native UPSERT
 * (ON CONFLICT) в репозитории.
 */
@Entity
@Table(name = "task_suggestion")
public class TaskSuggestion {

    /** Нормализованный текст (lower + trim). Используется как PK и для prefix-search. */
    @Id
    @Column(name = "text", nullable = false, length = 60)
    private String text;

    /** Оригинальное написание (как ввёл пользователь первый раз). Возвращается клиенту. */
    @Column(name = "text_display", nullable = false, length = 60)
    private String textDisplay;

    /** Сумма частоты использования среди всех пользователей. */
    @Column(name = "freq", nullable = false)
    private Long freq;

    /** Время последнего tracking'а. Используется в cleanup'е (DELETE WHERE last_used_at < NOW() - 1 year). */
    @Column(name = "last_used_at", nullable = false)
    private LocalDateTime lastUsedAt;

    /** Ручная блокировка (admin API). Скрывает подсказку из suggest, но запись остаётся в таблице. */
    @Column(name = "blocked", nullable = false)
    private boolean blocked;

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getTextDisplay() {
        return textDisplay;
    }

    public void setTextDisplay(String textDisplay) {
        this.textDisplay = textDisplay;
    }

    public Long getFreq() {
        return freq;
    }

    public void setFreq(Long freq) {
        this.freq = freq;
    }

    public LocalDateTime getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(LocalDateTime lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public boolean isBlocked() {
        return blocked;
    }

    public void setBlocked(boolean blocked) {
        this.blocked = blocked;
    }
}
