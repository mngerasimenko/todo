package ru.mngerasimenko.todolist.service;

import ru.mngerasimenko.todolist.dto.UserDto;

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
}
