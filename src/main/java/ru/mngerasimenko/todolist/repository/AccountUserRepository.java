package ru.mngerasimenko.todolist.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.mngerasimenko.todolist.model.AccountUser;
import ru.mngerasimenko.todolist.model.AccountUserId;

import java.util.List;
import java.util.Optional;

@Repository
public interface AccountUserRepository extends JpaRepository<AccountUser, AccountUserId> {

    List<AccountUser> findByUserId(Long userId);

    boolean existsByIdAccountIdAndIdUserId(Long accountId, Long userId);

    Optional<AccountUser> findByIdAccountIdAndIdUserId(Long accountId, Long userId);

    List<AccountUser> findByIdAccountId(Long accountId);

    /**
     * Удалить все записи участия пользователя в аккаунте по userId.
     * Используется при выходе пользователя из всех аккаунтов.
     */
    @Modifying
    @Query("DELETE FROM AccountUser au WHERE au.user.id = :userId AND au.account.id = :accountId")
    void deleteByAccountIdAndUserId(@Param("accountId") Long accountId, @Param("userId") Long userId);
}
