package ru.mngerasimenko.todolist.util;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * Прогоняет зарегистрированные колбэки так, как их зовёт Spring при УСПЕШНОМ коммите:
 * {@code beforeCommit} → {@code beforeCompletion} → {@code afterCommit} →
 * {@code afterCompletion(STATUS_COMMITTED)}.
 *
 * <p>{@code afterCommit} гоняется без try/catch между колбэками — ровно как
 * {@code TransactionSynchronizationUtils.invokeAfterCommit} в Spring: упавший колбэк
 * обрывает фазу и все зарегистрированные после него колбэки НЕ вызываются. Поэтому
 * драйвер воспроизводит дефект сбоя Redis после коммита: без перехвата исключения
 * в самом колбэке упавший eviction срывает следующие колбэки.
 */
public final class TransactionCommitTestUtils {

    private TransactionCommitTestUtils() {
    }

    /** Последовательность фаз коммита; исключение из {@code afterCommit} пробрасывается наружу. */
    public static void driveCommit() {
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        syncs.forEach(s -> s.beforeCommit(false));
        syncs.forEach(TransactionSynchronization::beforeCompletion);
        syncs.forEach(TransactionSynchronization::afterCommit);
        syncs.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
    }
}
