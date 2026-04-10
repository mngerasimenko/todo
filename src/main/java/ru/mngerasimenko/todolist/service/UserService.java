package ru.mngerasimenko.todolist.service;

import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.model.User;

import java.util.List;

/**
 * Сервис управления пользователями.
 * Предоставляет CRUD-операции и обновление персональных настроек (цвета задач).
 */
public interface UserService {

    /** Возвращает список всех пользователей */
    List<UserDto> getAll();

    /** Удаляет пользователя по ID */
    void delete(long id);

    /** Находит пользователя по имени */
    UserDto getUserByUserName(String userName);

    /** Находит пользователя по email */
    UserDto getUserByEmail(String email);

    /** Находит пользователя по auth ID (устройство) */
    UserDto getUserByAuthId(String authId);

    /** Создаёт нового пользователя (пароль шифруется BCrypt) */
    UserDto createUser(UserDto userDto);

    /** Обновляет данные пользователя по ID */
    UserDto updateUser(Long id, UserDto userDto);

    /** Возвращает пользователя по ID */
    UserDto getUserById(Long id);

    /** Обновляет цвета задач пользователя (HEX-формат #RRGGBB) */
    UserDto updateColors(Long id, String createdTaskColor, String completedTaskColor);

    /** Подтверждение email по токену из ссылки */
    void verifyEmail(String token);

    /** Повторная отправка письма верификации */
    void resendVerificationEmail(Long userId);

    /** Запрос сброса пароля (отправка письма) */
    void initiatePasswordReset(String email);

    /** Установка нового пароля по токену */
    void resetPassword(String token, String newPassword);

    /** Смена email с повторной верификацией */
    void changeEmail(Long userId, String newEmail);

    /** Обновить время последней активности пользователя */
    void updateLastActiveAt(Long userId);

    /** Найти неактивных пользователей для отправки напоминания */
    List<User> findInactiveUsersForReminder(int inactiveDays);

    /** Отметить что напоминание отправлено */
    void markReminderSent(Long userId);
}
