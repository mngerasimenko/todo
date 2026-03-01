package ru.mngerasimenko.todolist.service;

import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.exception.UserNotFoundException;
import ru.mngerasimenko.todolist.mapper.UserMapper;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.UserRepository;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    private final UserRepository repository;
    private final UserMapper mapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional(readOnly = true)
    public List<UserDto> getAll() {
        return repository.findAll().stream()
                .map(mapper::toDto)
                .toList();
    }

    @Override
    public void delete(long id) {
        repository.deleteById(id);
        log.info("Удалён пользователь: id={}", id);
    }

    @Override
    public UserDto getUserByUserName(String userName) {
        if (StringUtils.isBlank(userName)) {
            return null;
        }
        return mapper.toDto(repository.getUserByName(userName));
    }

    @Override
    public UserDto getUserByAuthId(String authId) {
        if (StringUtils.isBlank(authId)) {
            return null;
        }
        return mapper.toDto(repository.getUserByAuthId(authId));
    }

    @Override
    @Transactional(noRollbackFor = DataIntegrityViolationException.class)
    public UserDto createUser(UserDto userDto) {
        User user = mapper.toEntity(userDto);

        if (user.getAuthId() == null) {
            UUID uuid = UUID.randomUUID();
            user.setAuthId(uuid.toString());
        }

        // Хэшируем пароль перед сохранением
        user.setPassword(passwordEncoder.encode(user.getPassword()));

        // Атомарная вставка: нет TOCTOU — уникальность гарантирует БД (UNIQUE на name, email)
        try {
            User savedUser = repository.saveAndFlush(user);
            log.info("Создан пользователь: id={}, name='{}'", savedUser.getId(), savedUser.getName());
            return mapper.toDto(savedUser);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException(
                    "Пользователь с таким именем или email уже существует");
        }
    }

    @Override
    @Transactional
    public UserDto updateUser(Long id, UserDto userDto) {
        User existingUser = repository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + id));

        if (!existingUser.getEmail().equals(userDto.getEmail()) && existsByEmail(userDto.getEmail())) {
            throw new IllegalArgumentException("Email " + userDto.getEmail() + " is already taken");
        }

        existingUser.setEmail(userDto.getEmail());
        // Хэшируем пароль при обновлении
        existingUser.setPassword(passwordEncoder.encode(userDto.getPassword()));
        existingUser.setName(userDto.getName());

        User updatedUser = repository.save(existingUser);
        log.info("Обновлён пользователь: id={}, name='{}'", updatedUser.getId(), updatedUser.getName());
        return mapper.toDto(updatedUser);
    }

    @Override
    @Transactional(readOnly = true)
    public UserDto getUserById(Long id) {
        User user = repository.getUserById(id);
        if (user == null) {
            throw new UserNotFoundException("User not found with id: " + id);
        }
        return mapper.toDto(user);
    }

    @Override
    @Transactional
    public UserDto updateColors(Long id, String createdTaskColor, String completedTaskColor) {
        User user = repository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + id));
        user.setCreatedTaskColor(createdTaskColor);
        user.setCompletedTaskColor(completedTaskColor);
        User savedUser = repository.save(user);
        log.info("Обновлены цвета пользователя: id={}", id);
        return mapper.toDto(savedUser);
    }

    @Transactional(readOnly = true)
    public boolean existsByEmail(String email) {
        return repository.getUserByEmail(email) != null;
    }

    @Transactional(readOnly = true)
    public boolean existsByUserName(String userName) {
        return repository.getUserByName(userName) != null;
    }
}
