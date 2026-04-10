package ru.mngerasimenko.todolist.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.exception.TokenExpiredException;
import ru.mngerasimenko.todolist.exception.UserNotFoundException;
import ru.mngerasimenko.todolist.mapper.UserMapper;
import ru.mngerasimenko.todolist.model.TaskList;
import ru.mngerasimenko.todolist.model.TaskListRole;
import ru.mngerasimenko.todolist.model.TaskListUser;
import ru.mngerasimenko.todolist.model.TaskListUserId;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.TaskListRepository;
import ru.mngerasimenko.todolist.repository.TaskListUserRepository;
import ru.mngerasimenko.todolist.repository.TodoRepository;
import ru.mngerasimenko.todolist.repository.UserRepository;
import ru.mngerasimenko.todolist.settings.EmailProperties;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserRepository repository;

    @Mock
    private UserMapper mapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private EmailService emailService;

    @Mock
    private EmailProperties emailProperties;

    @Mock
    private TaskListUserRepository taskListUserRepository;

    @Mock
    private TaskListRepository taskListRepository;

    @Mock
    private TodoRepository todoRepository;

    @InjectMocks
    private UserServiceImpl userService;

    private User user;
    private UserDto userDto;


    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(1L);
        user.setAuthId("AuthId");
        user.setName("user");
        user.setEmail("user@mail.ru");
        user.setPassword("password");

        userDto = new UserDto();
        userDto.setId(1L);
        userDto.setAuthId("AuthId");
        userDto.setName("user");
        userDto.setEmail("user@mail.ru");
        userDto.setPassword("password");

        // Мок для создания токена верификации в createUser
        lenient().when(emailProperties.getVerificationTokenTtlHours()).thenReturn(24);
    }

    @Test
    void getAll_ReturnsListOfUsers() {
        User user2 = new User();
        user2.setId(2L);
        user2.setAuthId("AuthId2");
        user2.setName("user2");
        user2.setEmail("user2@mail.ru");
        user2.setPassword("pass2");

        UserDto dto2 = new UserDto();
        dto2.setId(2L);
        dto2.setAuthId("AuthId2");
        dto2.setName("user2");
        dto2.setEmail("user2@mail.ru");
        dto2.setPassword("pass2");

        List<User> users = Arrays.asList(user, user2);
        when(repository.findAll()).thenReturn(users);
        when(mapper.toDto(user)).thenReturn(userDto);
        when(mapper.toDto(user2)).thenReturn(dto2);

        List<UserDto> result = userService.getAll();

        assertThat(result).hasSize(2);
        assertThat(result).containsExactlyInAnyOrder(userDto, dto2);
        assertEquals(result.get(0).getId(), userDto.getId());
        assertEquals(result.get(1).getId(), dto2.getId());
        verify(repository, times(1)).findAll();
        verify(mapper, times(2)).toDto(any(User.class));
    }

    @Test
    void delete_WithExistingId_DeletesUser() {
        // Пользователь без списков
        User deletedUser = new User();
        deletedUser.setId(0L);
        when(repository.existsById(1L)).thenReturn(true);
        when(taskListUserRepository.findByUserId(1L)).thenReturn(List.of());
        when(repository.findById(0L)).thenReturn(Optional.of(deletedUser));

        userService.delete(1L);

        verify(repository, times(1)).existsById(1L);
        verify(taskListUserRepository, times(1)).findByUserId(1L);
        verify(todoRepository, times(1)).reassignUser(1L, 0L);
        verify(todoRepository, times(1)).reassignCompletorUser(1L, 0L);
        verify(repository, times(1)).deleteById(1L);
    }

    @Test
    void delete_SoleListMember_DeletesListEntirely() {
        // Пользователь — единственный участник списка → удаляем список целиком
        User deletedUser = new User();
        deletedUser.setId(0L);

        TaskList list = new TaskList();
        list.setId(10L);

        TaskListUser membership = new TaskListUser();
        membership.setTaskList(list);
        membership.setUser(user);
        membership.setRole(TaskListRole.ADMIN);

        when(repository.existsById(1L)).thenReturn(true);
        when(taskListUserRepository.findByUserId(1L)).thenReturn(List.of(membership));
        when(taskListUserRepository.findByIdListId(10L)).thenReturn(List.of(membership));
        when(repository.findById(0L)).thenReturn(Optional.of(deletedUser));

        userService.delete(1L);

        // Список удалён целиком
        verify(todoRepository).deleteByListId(10L);
        verify(taskListUserRepository).deleteByListId(10L);
        verify(taskListRepository).deleteByListId(10L);
        // Приватные задачи отдельно не удалялись (весь список удалён)
        verify(todoRepository, never()).deletePrivateTodosByListIdAndUserId(anyLong(), anyLong());
        verify(repository).deleteById(1L);
    }

    @Test
    void delete_AdminWithOtherMembers_TransfersAdminRole() {
        // Пользователь — ADMIN списка с другими участниками → передаём ADMIN
        User deletedUser = new User();
        deletedUser.setId(0L);

        User otherUser = new User();
        otherUser.setId(2L);

        TaskList list = new TaskList();
        list.setId(10L);

        TaskListUser adminMembership = new TaskListUser();
        adminMembership.setTaskList(list);
        adminMembership.setUser(user);
        adminMembership.setRole(TaskListRole.ADMIN);

        TaskListUser otherMembership = new TaskListUser();
        otherMembership.setTaskList(list);
        otherMembership.setUser(otherUser);
        otherMembership.setRole(TaskListRole.USER);

        when(repository.existsById(1L)).thenReturn(true);
        when(taskListUserRepository.findByUserId(1L)).thenReturn(List.of(adminMembership));
        when(taskListUserRepository.findByIdListId(10L)).thenReturn(List.of(adminMembership, otherMembership));
        when(repository.findById(0L)).thenReturn(Optional.of(deletedUser));

        userService.delete(1L);

        // ADMIN передан другому участнику
        assertThat(otherMembership.getRole()).isEqualTo(TaskListRole.ADMIN);
        // Приватные задачи удалены, связь удалена
        verify(todoRepository).deletePrivateTodosByListIdAndUserId(10L, 1L);
        verify(taskListUserRepository).deleteByListIdAndUserId(10L, 1L);
        // Список НЕ удалён
        verify(taskListRepository, never()).deleteByListId(10L);
        verify(repository).deleteById(1L);
    }

    @Test
    void delete_UserInMultipleLists_HandlesEachList() {
        // Пользователь в двух списках: один пустой, другой с участниками
        User deletedUser = new User();
        deletedUser.setId(0L);

        User otherUser = new User();
        otherUser.setId(2L);

        TaskList list1 = new TaskList();
        list1.setId(10L);
        TaskList list2 = new TaskList();
        list2.setId(20L);

        // Список 1: единственный участник
        TaskListUser membership1 = new TaskListUser();
        membership1.setTaskList(list1);
        membership1.setUser(user);
        membership1.setRole(TaskListRole.ADMIN);

        // Список 2: есть другой участник
        TaskListUser membership2 = new TaskListUser();
        membership2.setTaskList(list2);
        membership2.setUser(user);
        membership2.setRole(TaskListRole.USER);

        TaskListUser otherMembership = new TaskListUser();
        otherMembership.setTaskList(list2);
        otherMembership.setUser(otherUser);
        otherMembership.setRole(TaskListRole.ADMIN);

        when(repository.existsById(1L)).thenReturn(true);
        when(taskListUserRepository.findByUserId(1L)).thenReturn(List.of(membership1, membership2));
        when(taskListUserRepository.findByIdListId(10L)).thenReturn(List.of(membership1));
        when(taskListUserRepository.findByIdListId(20L)).thenReturn(List.of(membership2, otherMembership));
        when(repository.findById(0L)).thenReturn(Optional.of(deletedUser));

        userService.delete(1L);

        // Список 1: удалён целиком
        verify(todoRepository).deleteByListId(10L);
        verify(taskListUserRepository).deleteByListId(10L);
        verify(taskListRepository).deleteByListId(10L);

        // Список 2: приватные удалены, связь удалена, список остался
        verify(todoRepository).deletePrivateTodosByListIdAndUserId(20L, 1L);
        verify(taskListUserRepository).deleteByListIdAndUserId(20L, 1L);
        verify(taskListRepository, never()).deleteByListId(20L);

        // Публичные задачи переназначены
        verify(todoRepository).reassignUser(1L, 0L);
        verify(todoRepository).reassignCompletorUser(1L, 0L);
        verify(repository).deleteById(1L);
    }

    @Test
    void delete_WithNonExistentId_ThrowsUserNotFoundException() {
        when(repository.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> userService.delete(999L))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessage("User not found with id: 999");

        verify(repository, times(1)).existsById(999L);
        verify(repository, never()).deleteById(anyLong());
    }

    @Test
    void getUserByUserName_WithValidUserName_ReturnsUserDto() {
        when(repository.getUserByName("user")).thenReturn(user);
        when(mapper.toDto(user)).thenReturn(userDto);

        UserDto result = userService.getUserByUserName("user");

        assertThat(result).isEqualTo(userDto);
        verify(repository, times(1)).getUserByName("user");
        verify(mapper, times(1)).toDto(user);
    }

    @Test
    void getUserByUserName_WithBlankUserName_ReturnsNull() {
        UserDto result = userService.getUserByUserName("   ");

        assertThat(result).isNull();
        verify(repository, never()).getUserByName(anyString());
    }

    @Test
    void getUserByUserName_WithNullUserName_ReturnsNull() {
        UserDto result = userService.getUserByUserName(null);

        assertThat(result).isNull();
        verify(repository, never()).getUserByName(anyString());
    }

    @Test
    void getUserByAuthId_WithValidAuthId_ReturnsUserDto() {
        when(repository.getUserByAuthId("AuthId")).thenReturn(user);
        when(mapper.toDto(user)).thenReturn(userDto);

        UserDto result = userService.getUserByAuthId("AuthId");

        assertThat(result).isEqualTo(userDto);
        verify(repository, times(1)).getUserByAuthId("AuthId");
        verify(mapper, times(1)).toDto(user);
    }

    @Test
    void getUserByAuthId_WithBlankAuthId_ReturnsNull() {
        UserDto result = userService.getUserByAuthId("   ");

        assertThat(result).isNull();
        verify(repository, never()).getUserByAuthId(anyString());
    }

    @Test
    void getUserByAuthId_WithNullAuthId_ReturnsNull() {
        UserDto result = userService.getUserByAuthId(null);

        assertThat(result).isNull();
        verify(repository, never()).getUserByAuthId(anyString());
    }

    @Test
    void createUser_WithNewUser_ReturnsCreatedUserDto() {
        UserDto newUserDto = new UserDto();
        newUserDto.setName("newuser");
        newUserDto.setEmail("new@mail.ru");
        newUserDto.setPassword("newpass");

        User newUser = new User();
        newUser.setName("newuser");
        newUser.setEmail("new@mail.ru");
        newUser.setPassword("newpass");

        User savedUser = new User();
        savedUser.setId(2L);
        savedUser.setName("newuser");
        savedUser.setEmail("new@mail.ru");
        savedUser.setPassword("newpass");
        savedUser.setAuthId("generated-auth-id");

        when(mapper.toEntity(newUserDto)).thenReturn(newUser);
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$encodedPassword");
        when(repository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(mapper.toDto(savedUser)).thenReturn(newUserDto);

        UserDto result = userService.createUser(newUserDto);

        assertThat(result).isEqualTo(newUserDto);
        verify(repository, times(1)).saveAndFlush(any(User.class));
        verify(mapper, times(1)).toDto(savedUser);
        verify(passwordEncoder, times(1)).encode(anyString());
    }

    @Test
    void createUser_WithDuplicateEmailOrName_ThrowsIllegalArgumentException() {
        // Уникальность гарантирует БД — saveAndFlush бросает DataIntegrityViolationException
        UserDto existingUserDto = new UserDto();
        existingUserDto.setName("existing");
        existingUserDto.setEmail("existing@mail.ru");
        existingUserDto.setPassword("pass12345");

        User existingUser = new User();
        existingUser.setName("existing");
        existingUser.setEmail("existing@mail.ru");
        existingUser.setPassword("pass12345"); // mapper устанавливает пароль из DTO

        when(mapper.toEntity(existingUserDto)).thenReturn(existingUser);
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hash");
        when(repository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> userService.createUser(existingUserDto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Не удалось создать аккаунт");
    }

    @Test
    void createUser_WithNullAuthId_GeneratesNewAuthId() {
        UserDto newUserDto = new UserDto();
        newUserDto.setName("newuser");
        newUserDto.setEmail("new@mail.ru");
        newUserDto.setPassword("newpass");
        newUserDto.setAuthId(null);

        User newUser = new User();
        newUser.setName("newuser");
        newUser.setEmail("new@mail.ru");
        newUser.setPassword("newpass");
        newUser.setAuthId(null);

        when(mapper.toEntity(newUserDto)).thenReturn(newUser);
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$encodedPassword");
        when(repository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(2L);
            if (saved.getAuthId() == null) {
                saved.setAuthId(UUID.randomUUID().toString());
            }
            return saved;
        });
        when(mapper.toDto(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            UserDto dto = new UserDto();
            dto.setId(u.getId());
            dto.setName(u.getName());
            dto.setEmail(u.getEmail());
            dto.setPassword(u.getPassword());
            dto.setAuthId(u.getAuthId());
            return dto;
        });

        UserDto result = userService.createUser(newUserDto);

        assertThat(result.getAuthId()).isNotNull();
        assertThat(result.getAuthId()).isNotEmpty();
        verify(repository, times(1)).saveAndFlush(any(User.class));
    }

    @Test
    void updateUser_WithValidIdAndDto_ReturnsUpdatedUserDto() {
        UserDto updatedDto = new UserDto();
        updatedDto.setName("updated");
        updatedDto.setEmail("updated@mail.ru");
        updatedDto.setPassword("newpass");

        User existingUser = new User();
        existingUser.setId(1L);
        existingUser.setName("old");
        existingUser.setEmail("old@mail.ru");
        existingUser.setPassword("oldpass");

        User updatedUser = new User();
        updatedUser.setId(1L);
        updatedUser.setName("updated");
        updatedUser.setEmail("updated@mail.ru");
        updatedUser.setPassword("newpass");

        when(repository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(repository.getUserByEmail("updated@mail.ru")).thenReturn(null);
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$encodedPassword");
        when(repository.save(any(User.class))).thenReturn(updatedUser);
        when(mapper.toDto(updatedUser)).thenReturn(updatedDto);

        UserDto result = userService.updateUser(1L, updatedDto);

        assertThat(result).isEqualTo(updatedDto);
        verify(repository, times(1)).save(any(User.class));
        verify(repository, times(1)).findById(1L);
        verify(passwordEncoder, times(1)).encode(anyString());
    }

    @Test
    void updateUser_WithNonExistentId_ThrowsUserNotFoundException() {
        UserDto updatedDto = new UserDto();
        updatedDto.setName("updated");
        updatedDto.setEmail("updated@mail.ru");
        updatedDto.setPassword("newpass");

        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateUser(999L, updatedDto))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessage("User not found with id: 999");

        verify(repository, never()).save(any(User.class));
    }

    @Test
    void updateUser_WithExistingEmail_ThrowsIllegalArgumentException() {
        UserDto updatedDto = new UserDto();
        updatedDto.setName("updated");
        updatedDto.setEmail("existing@mail.ru");
        updatedDto.setPassword("newpass");

        User existingUser = new User();
        existingUser.setId(1L);
        existingUser.setName("old");
        existingUser.setEmail("old@mail.ru");
        existingUser.setPassword("oldpass");

        User existingEmailUser = new User();
        existingEmailUser.setEmail("existing@mail.ru");

        when(repository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(repository.getUserByEmail("existing@mail.ru")).thenReturn(existingEmailUser);

        assertThatThrownBy(() -> userService.updateUser(1L, updatedDto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Email existing@mail.ru is already taken");

        verify(repository, never()).save(any(User.class));
    }

    @Test
    void getUserById_WithValidId_ReturnsUserDto() {
        when(repository.getUserById(1L)).thenReturn(user);
        when(mapper.toDto(user)).thenReturn(userDto);

        UserDto result = userService.getUserById(1L);

        assertThat(result).isEqualTo(userDto);
        verify(repository, times(1)).getUserById(1L);
        verify(mapper, times(1)).toDto(user);
    }

    @Test
    void getUserById_WithNonExistentId_ThrowsUserNotFoundException() {
        when(repository.getUserById(999L)).thenReturn(null);

        assertThatThrownBy(() -> userService.getUserById(999L))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessage("User not found with id: 999");

        verify(repository, times(1)).getUserById(999L);
    }

    @Test
    void existsByEmail_WithExistingEmail_ReturnsTrue() {
        when(repository.getUserByEmail("test@mail.ru")).thenReturn(user);

        boolean result = userService.existsByEmail("test@mail.ru");

        assertThat(result).isTrue();
        verify(repository, times(1)).getUserByEmail("test@mail.ru");
    }

    @Test
    void existsByEmail_WithNonExistentEmail_ReturnsFalse() {
        when(repository.getUserByEmail("nonexistent@mail.ru")).thenReturn(null);

        boolean result = userService.existsByEmail("nonexistent@mail.ru");

        assertThat(result).isFalse();
        verify(repository, times(1)).getUserByEmail("nonexistent@mail.ru");
    }

    @Test
    void existsByUserName_WithExistingUserName_ReturnsTrue() {
        when(repository.getUserByName("user")).thenReturn(user);

        boolean result = userService.existsByUserName("user");

        assertThat(result).isTrue();
        verify(repository, times(1)).getUserByName("user");
    }

    @Test
    void existsByUserName_WithNonExistentUserName_ReturnsFalse() {
        when(repository.getUserByName("nonexistent")).thenReturn(null);

        boolean result = userService.existsByUserName("nonexistent");

        assertThat(result).isFalse();
        verify(repository, times(1)).getUserByName("nonexistent");
    }

    @Test
    void updateColors_WithValidId_ReturnsUpdatedUserDto() {
        User existingUser = new User();
        existingUser.setId(1L);
        existingUser.setName("user");
        existingUser.setEmail("user@mail.ru");
        existingUser.setPassword("$2a$10$hash");
        existingUser.setCreatedTaskColor("#4285F4");
        existingUser.setCompletedTaskColor("#34A853");

        User savedUser = new User();
        savedUser.setId(1L);
        savedUser.setName("user");
        savedUser.setEmail("user@mail.ru");
        savedUser.setPassword("$2a$10$hash");
        savedUser.setCreatedTaskColor("#FF0000");
        savedUser.setCompletedTaskColor("#00FF00");

        UserDto updatedDto = new UserDto();
        updatedDto.setId(1L);
        updatedDto.setCreatedTaskColor("#FF0000");
        updatedDto.setCompletedTaskColor("#00FF00");

        when(repository.findById(1L)).thenReturn(java.util.Optional.of(existingUser));
        when(repository.save(any(User.class))).thenReturn(savedUser);
        when(mapper.toDto(savedUser)).thenReturn(updatedDto);

        UserDto result = userService.updateColors(1L, "#FF0000", "#00FF00");

        assertThat(result).isNotNull();
        assertThat(result.getCreatedTaskColor()).isEqualTo("#FF0000");
        assertThat(result.getCompletedTaskColor()).isEqualTo("#00FF00");
        verify(repository, times(1)).findById(1L);
        verify(repository, times(1)).save(existingUser);
    }

    @Test
    void updateColors_WithNonExistentId_ThrowsUserNotFoundException() {
        when(repository.findById(999L)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> userService.updateColors(999L, "#FF0000", "#00FF00"))
                .isInstanceOf(ru.mngerasimenko.todolist.exception.UserNotFoundException.class)
                .hasMessage("User not found with id: 999");

        verify(repository, never()).save(any(User.class));
    }

    // ===== verifyEmail =====

    @Test
    void verifyEmail_WithValidToken_SetsEmailVerifiedAndClearsToken() {
        // Подготавливаем пользователя с валидным токеном
        String rawToken = "valid-token-123";
        String tokenHash = UserServiceImpl.sha256(rawToken);
        User foundUser = new User();
        foundUser.setId(1L);
        foundUser.setEmailVerified(false);
        foundUser.setEmailVerificationToken(tokenHash);
        foundUser.setEmailVerificationExpiresAt(LocalDateTime.now().plusHours(1));

        when(repository.findByEmailVerificationToken(tokenHash)).thenReturn(foundUser);
        when(repository.save(any(User.class))).thenReturn(foundUser);

        userService.verifyEmail(rawToken);

        assertThat(foundUser.isEmailVerified()).isTrue();
        assertThat(foundUser.getEmailVerificationToken()).isNull();
        assertThat(foundUser.getEmailVerificationExpiresAt()).isNull();
        verify(repository, times(1)).findByEmailVerificationToken(tokenHash);
        verify(repository, times(1)).save(foundUser);
    }

    @Test
    void verifyEmail_WithInvalidToken_ThrowsTokenExpiredException() {
        // Токен не найден в БД
        String rawToken = "invalid-token";
        String tokenHash = UserServiceImpl.sha256(rawToken);

        when(repository.findByEmailVerificationToken(tokenHash)).thenReturn(null);

        assertThatThrownBy(() -> userService.verifyEmail(rawToken))
                .isInstanceOf(TokenExpiredException.class)
                .hasMessage("Невалидный токен верификации");

        verify(repository, never()).save(any(User.class));
    }

    @Test
    void verifyEmail_WithExpiredToken_ThrowsTokenExpiredException() {
        // Токен найден, но срок истёк
        String rawToken = "expired-token";
        String tokenHash = UserServiceImpl.sha256(rawToken);
        User foundUser = new User();
        foundUser.setId(1L);
        foundUser.setEmailVerified(false);
        foundUser.setEmailVerificationToken(tokenHash);
        foundUser.setEmailVerificationExpiresAt(LocalDateTime.now().minusHours(1));

        when(repository.findByEmailVerificationToken(tokenHash)).thenReturn(foundUser);

        assertThatThrownBy(() -> userService.verifyEmail(rawToken))
                .isInstanceOf(TokenExpiredException.class)
                .hasMessage("Токен верификации истёк");

        verify(repository, never()).save(any(User.class));
    }

    // ===== resendVerificationEmail =====

    @Test
    void resendVerificationEmail_WithUnverifiedUser_GeneratesNewTokenAndSendsEmail() {
        // Пользователь найден, email не подтверждён
        User foundUser = new User();
        foundUser.setId(1L);
        foundUser.setEmail("user@mail.ru");
        foundUser.setEmailVerified(false);

        when(repository.findById(1L)).thenReturn(Optional.of(foundUser));
        when(emailProperties.getVerificationTokenTtlHours()).thenReturn(24);
        when(repository.save(any(User.class))).thenReturn(foundUser);

        userService.resendVerificationEmail(1L);

        // Проверяем что токен был обновлён
        assertThat(foundUser.getEmailVerificationToken()).isNotNull();
        assertThat(foundUser.getEmailVerificationExpiresAt()).isNotNull();
        assertThat(foundUser.getEmailVerificationExpiresAt()).isAfter(LocalDateTime.now());
        verify(repository, times(1)).findById(1L);
        verify(repository, times(1)).save(foundUser);
        verify(emailService, times(1)).sendVerificationEmail(eq("user@mail.ru"), anyString());
    }

    @Test
    void resendVerificationEmail_WithNonExistentUser_ThrowsUserNotFoundException() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.resendVerificationEmail(999L))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessage("User not found with id: 999");

        verify(repository, never()).save(any(User.class));
        verify(emailService, never()).sendVerificationEmail(anyString(), anyString());
    }

    @Test
    void resendVerificationEmail_WithAlreadyVerifiedEmail_ThrowsIllegalArgumentException() {
        // Пользователь найден, email уже подтверждён
        User foundUser = new User();
        foundUser.setId(1L);
        foundUser.setEmail("user@mail.ru");
        foundUser.setEmailVerified(true);

        when(repository.findById(1L)).thenReturn(Optional.of(foundUser));

        assertThatThrownBy(() -> userService.resendVerificationEmail(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Email уже подтверждён");

        verify(repository, never()).save(any(User.class));
        verify(emailService, never()).sendVerificationEmail(anyString(), anyString());
    }

    // ===== initiatePasswordReset =====

    @Test
    void initiatePasswordReset_WithVerifiedUser_SendsResetEmail() {
        // Пользователь найден и email подтверждён
        User foundUser = new User();
        foundUser.setId(1L);
        foundUser.setEmail("user@mail.ru");
        foundUser.setEmailVerified(true);

        when(repository.getUserByEmail("user@mail.ru")).thenReturn(foundUser);
        when(emailProperties.getResetTokenTtlHours()).thenReturn(1);
        when(repository.save(any(User.class))).thenReturn(foundUser);

        userService.initiatePasswordReset("user@mail.ru");

        // Проверяем что токен сброса пароля был установлен
        assertThat(foundUser.getPasswordResetToken()).isNotNull();
        assertThat(foundUser.getPasswordResetExpiresAt()).isNotNull();
        assertThat(foundUser.getPasswordResetExpiresAt()).isAfter(LocalDateTime.now());
        verify(repository, times(1)).getUserByEmail("user@mail.ru");
        verify(repository, times(1)).save(foundUser);
        verify(emailService, times(1)).sendPasswordResetEmail(eq("user@mail.ru"), anyString());
    }

    @Test
    void initiatePasswordReset_WithNonExistentUser_DoesNothingSilently() {
        // Пользователь не найден — молча ничего не делаем (защита от перечисления)
        when(repository.getUserByEmail("unknown@mail.ru")).thenReturn(null);

        userService.initiatePasswordReset("unknown@mail.ru");

        verify(repository, times(1)).getUserByEmail("unknown@mail.ru");
        verify(repository, never()).save(any(User.class));
        verify(emailService, never()).sendPasswordResetEmail(anyString(), anyString());
    }

    @Test
    void initiatePasswordReset_WithUnverifiedEmail_DoesNothingSilently() {
        // Пользователь найден, но email не подтверждён — молча ничего не делаем
        User foundUser = new User();
        foundUser.setId(1L);
        foundUser.setEmail("user@mail.ru");
        foundUser.setEmailVerified(false);

        when(repository.getUserByEmail("user@mail.ru")).thenReturn(foundUser);

        userService.initiatePasswordReset("user@mail.ru");

        verify(repository, times(1)).getUserByEmail("user@mail.ru");
        verify(repository, never()).save(any(User.class));
        verify(emailService, never()).sendPasswordResetEmail(anyString(), anyString());
    }

    // ===== resetPassword =====

    @Test
    void resetPassword_WithValidToken_UpdatesPasswordAndClearsToken() {
        // Подготавливаем пользователя с валидным токеном сброса
        String rawToken = "reset-token-123";
        String tokenHash = UserServiceImpl.sha256(rawToken);
        User foundUser = new User();
        foundUser.setId(1L);
        foundUser.setPasswordResetToken(tokenHash);
        foundUser.setPasswordResetExpiresAt(LocalDateTime.now().plusHours(1));
        foundUser.setPassword("oldEncodedPassword");

        when(repository.findByPasswordResetToken(tokenHash)).thenReturn(foundUser);
        when(passwordEncoder.encode("newPassword123")).thenReturn("$2a$10$newEncodedPassword");
        when(repository.save(any(User.class))).thenReturn(foundUser);

        userService.resetPassword(rawToken, "newPassword123");

        assertThat(foundUser.getPassword()).isEqualTo("$2a$10$newEncodedPassword");
        assertThat(foundUser.getPasswordResetToken()).isNull();
        assertThat(foundUser.getPasswordResetExpiresAt()).isNull();
        verify(repository, times(1)).findByPasswordResetToken(tokenHash);
        verify(passwordEncoder, times(1)).encode("newPassword123");
        verify(repository, times(1)).save(foundUser);
    }

    @Test
    void resetPassword_WithInvalidToken_ThrowsTokenExpiredException() {
        // Токен не найден в БД
        String rawToken = "invalid-reset-token";
        String tokenHash = UserServiceImpl.sha256(rawToken);

        when(repository.findByPasswordResetToken(tokenHash)).thenReturn(null);

        assertThatThrownBy(() -> userService.resetPassword(rawToken, "newPassword"))
                .isInstanceOf(TokenExpiredException.class)
                .hasMessage("Невалидный токен сброса пароля");

        verify(repository, never()).save(any(User.class));
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void resetPassword_WithExpiredToken_ThrowsTokenExpiredException() {
        // Токен найден, но срок истёк
        String rawToken = "expired-reset-token";
        String tokenHash = UserServiceImpl.sha256(rawToken);
        User foundUser = new User();
        foundUser.setId(1L);
        foundUser.setPasswordResetToken(tokenHash);
        foundUser.setPasswordResetExpiresAt(LocalDateTime.now().minusHours(1));

        when(repository.findByPasswordResetToken(tokenHash)).thenReturn(foundUser);

        assertThatThrownBy(() -> userService.resetPassword(rawToken, "newPassword"))
                .isInstanceOf(TokenExpiredException.class)
                .hasMessage("Токен сброса пароля истёк");

        verify(repository, never()).save(any(User.class));
        verify(passwordEncoder, never()).encode(anyString());
    }

    // ===== changeEmail =====

    @Test
    void changeEmail_Success_UpdatesEmailAndSendsVerification() {
        // Пользователь найден, новый email свободен
        User foundUser = new User();
        foundUser.setId(1L);
        foundUser.setEmail("old@mail.ru");
        foundUser.setEmailVerified(true);

        when(repository.findById(1L)).thenReturn(Optional.of(foundUser));
        when(repository.getUserByEmail("new@mail.ru")).thenReturn(null);
        when(emailProperties.getVerificationTokenTtlHours()).thenReturn(24);
        when(repository.saveAndFlush(any(User.class))).thenReturn(foundUser);

        userService.changeEmail(1L, "new@mail.ru");

        // Проверяем обновление email и сброс верификации
        assertThat(foundUser.getEmail()).isEqualTo("new@mail.ru");
        assertThat(foundUser.isEmailVerified()).isFalse();
        assertThat(foundUser.getEmailVerificationToken()).isNotNull();
        assertThat(foundUser.getEmailVerificationExpiresAt()).isNotNull();
        assertThat(foundUser.getEmailVerificationExpiresAt()).isAfter(LocalDateTime.now());
        verify(repository, times(1)).findById(1L);
        verify(repository, times(1)).saveAndFlush(foundUser);
        verify(emailService, times(1)).sendVerificationEmail(eq("new@mail.ru"), anyString());
    }

    @Test
    void changeEmail_EmailAlreadyTaken_ThrowsIllegalArgumentException() {
        // Пользователь найден, но новый email занят другим пользователем
        User foundUser = new User();
        foundUser.setId(1L);
        foundUser.setEmail("old@mail.ru");

        User otherUser = new User();
        otherUser.setId(2L);
        otherUser.setEmail("taken@mail.ru");

        when(repository.findById(1L)).thenReturn(Optional.of(foundUser));
        when(repository.getUserByEmail("taken@mail.ru")).thenReturn(otherUser);

        assertThatThrownBy(() -> userService.changeEmail(1L, "taken@mail.ru"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Email taken@mail.ru уже используется");

        verify(repository, never()).saveAndFlush(any(User.class));
        verify(emailService, never()).sendVerificationEmail(anyString(), anyString());
    }

    @Test
    void changeEmail_SameEmailAllowed_DoesNotThrowException() {
        // Пользователь меняет email на тот же самый — не должно быть исключения
        User foundUser = new User();
        foundUser.setId(1L);
        foundUser.setEmail("same@mail.ru");
        foundUser.setEmailVerified(true);

        when(repository.findById(1L)).thenReturn(Optional.of(foundUser));
        when(repository.getUserByEmail("same@mail.ru")).thenReturn(foundUser);
        when(emailProperties.getVerificationTokenTtlHours()).thenReturn(24);
        when(repository.saveAndFlush(any(User.class))).thenReturn(foundUser);

        userService.changeEmail(1L, "same@mail.ru");

        // Email остался тем же, но верификация сброшена
        assertThat(foundUser.getEmail()).isEqualTo("same@mail.ru");
        assertThat(foundUser.isEmailVerified()).isFalse();
        verify(repository, times(1)).saveAndFlush(foundUser);
        verify(emailService, times(1)).sendVerificationEmail(eq("same@mail.ru"), anyString());
    }

    @Test
    void changeEmail_UserNotFound_ThrowsUserNotFoundException() {
        // Пользователь не найден
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.changeEmail(999L, "new@mail.ru"))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessage("User not found with id: 999");

        verify(repository, never()).saveAndFlush(any(User.class));
        verify(emailService, never()).sendVerificationEmail(anyString(), anyString());
    }

    // ===== updateLastActiveAt =====

    @Test
    void updateLastActiveAt_CallsRepository() {
        userService.updateLastActiveAt(1L);

        verify(repository, times(1)).updateLastActiveAt(eq(1L), any(LocalDateTime.class));
    }

    // ===== findInactiveUsersForReminder =====

    @Test
    void findInactiveUsersForReminder_ReturnsFirstReminder() {
        user.setReminderCount(0);
        when(repository.findInactiveUsersForReminder(any(LocalDateTime.class), eq(3)))
                .thenReturn(List.of(user));

        List<User> result = userService.findInactiveUsersForReminder(3);

        assertThat(result).hasSize(1);
    }

    @Test
    void findInactiveUsersForReminder_FiltersSecondReminderByInterval() {
        // Второе напоминание — через 7 дней после первого. Отправлено 2 дня назад — рано.
        user.setReminderCount(1);
        user.setLastReminderSentAt(LocalDateTime.now().minusDays(2));
        when(repository.findInactiveUsersForReminder(any(LocalDateTime.class), eq(3)))
                .thenReturn(List.of(user));

        List<User> result = userService.findInactiveUsersForReminder(3);

        assertThat(result).isEmpty();
    }

    @Test
    void findInactiveUsersForReminder_AllowsSecondReminderAfterInterval() {
        // Второе напоминание — через 7 дней. Отправлено 8 дней назад — пора.
        user.setReminderCount(1);
        user.setLastReminderSentAt(LocalDateTime.now().minusDays(8));
        when(repository.findInactiveUsersForReminder(any(LocalDateTime.class), eq(3)))
                .thenReturn(List.of(user));

        List<User> result = userService.findInactiveUsersForReminder(3);

        assertThat(result).hasSize(1);
    }

    @Test
    void findInactiveUsersForReminder_AllowsThirdReminderAfter30Days() {
        // Третье напоминание — через 30 дней. Отправлено 31 день назад — пора.
        user.setReminderCount(2);
        user.setLastReminderSentAt(LocalDateTime.now().minusDays(31));
        when(repository.findInactiveUsersForReminder(any(LocalDateTime.class), eq(3)))
                .thenReturn(List.of(user));

        List<User> result = userService.findInactiveUsersForReminder(3);

        assertThat(result).hasSize(1);
    }

    // ===== markReminderSent =====

    @Test
    void markReminderSent_IncrementsCountAndSetsTimestamp() {
        user.setReminderCount(0);
        when(repository.findById(1L)).thenReturn(Optional.of(user));

        userService.markReminderSent(1L);

        assertThat(user.getLastReminderSentAt()).isNotNull();
        assertThat(user.getReminderCount()).isEqualTo(1);
    }

    @Test
    void markReminderSent_UserNotFound_ThrowsException() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.markReminderSent(99L))
                .isInstanceOf(UserNotFoundException.class);
    }
}
