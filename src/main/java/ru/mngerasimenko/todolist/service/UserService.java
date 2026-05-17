package ru.mngerasimenko.todolist.service;

import ru.mngerasimenko.todolist.dto.AuthUserDto;
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

    /** Находит пользователя по email (включая password для BCrypt-проверки в Spring Security). Без кэша. */
    UserDto getUserByEmail(String email);

    /**
     * Кэшированный lookup для Spring Security auth-путей (JWT-filter + DaoAuthenticationProvider).
     * Возвращает {@link AuthUserDto} (email + BCrypt-hash password) — отдельный тип от {@link UserDto}
     * нужен потому, что {@code UserDto.password} имеет {@code @JsonIgnore} и теряется при
     * Jackson-сериализации в Redis.
     * <p>
     * TTL 60 сек, ключ = email.toLowerCase(); инвалидация — через {@code evictUserCache}
     * во всех мутациях, меняющих password/email/роли пользователя.
     * НЕ использовать вне security-путей.
     */
    AuthUserDto getUserByEmailForAuth(String email);

    /**
     * Возвращает UserDto для HTTP-ответа (/api/users/me) с password=null.
     * Кэшируется в Redis (users-me). Не использовать в auth-путях — password нужен там.
     */
    UserDto getUserDtoForResponse(String email);

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

    /**
     * Сменить язык email-уведомлений пользователя.
     * Влияет на все будущие письма (verify, reset, invite, inactive-reminder).
     */
    void updateEmailLocale(Long userId, String locale);

    /** Обновить время последней активности пользователя */
    void updateLastActiveAt(Long userId);

    /** Найти неактивных пользователей для отправки напоминания */
    List<User> findInactiveUsersForReminder(int inactiveDays);

    /** Отметить что напоминание отправлено */
    void markReminderSent(Long userId);

    /**
     * Отписать пользователя от reminder-напоминаний (3d + 7d) по одноразовому токену.
     * Ставит reminderOptOut=true и очищает unsubscribeToken.
     *
     * @return BCP-47 локаль пользователя (preferredEmailLocale) — для рендеринга
     *         success-HTML на нужном языке в контроллере.
     * @throws ru.mngerasimenko.todolist.exception.UserNotFoundException если токен
     *         невалиден или уже использован.
     */
    String unsubscribeFromReminders(String unsubscribeToken);

    /**
     * Кандидаты на 3-дневное onboarding-напоминание (Phase 3.3).
     * @param days сколько дней должно пройти с регистрации без возврата (обычно 3).
     */
    List<User> findOnboardingReminderCandidates(int days);

    /**
     * Пометить, что 3-дневное onboarding-напоминание отправлено. Однократно.
     * После этого пользователь не попадёт в {@link #findOnboardingReminderCandidates(int)}.
     */
    void markOnboardingReminderSent(Long userId);

    /**
     * Сгенерировать новый одноразовый unsubscribe-токен (256 бит, hex) и сохранить
     * его в {@code user.unsubscribeToken}. Возвращает токен для подстановки в email-footer.
     * <p>
     * Перезаписывает предыдущий токен — гарантирует, что в БД одновременно живёт только
     * один активный токен на пользователя (это позволяет UNIQUE-constraint в миграции 022b).
     *
     * @throws ru.mngerasimenko.todolist.exception.UserNotFoundException если userId не существует
     */
    String issueUnsubscribeToken(Long userId);
}
