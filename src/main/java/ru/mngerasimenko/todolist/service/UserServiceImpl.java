package ru.mngerasimenko.todolist.service;

import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.exception.UserNotFoundException;
import ru.mngerasimenko.todolist.mapper.UserMapper;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.UserRepository;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    private final UserRepository repository;
    private final UserMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public List<UserDto> getAll() {
        return repository.findAll().stream()
                .map(mapper::toDto)
                .collect(Collectors.toList());
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
    @Transactional
    public UserDto createUser(UserDto userDto) {
        if (existsByEmail(userDto.getEmail())) {
            throw new IllegalArgumentException("User with email " + userDto.getEmail() + " already exists");
        }
        if (existsByUserName(userDto.getName())) {
            throw new IllegalArgumentException("User with name " + userDto.getName() + " already exists");
        }

        User user = mapper.toEntity(userDto);

        if (user.getAuthId() == null) {
            UUID uuid = UUID.randomUUID();
            user.setAuthId(uuid.toString());
        }

        User savedUser = repository.save(user);
        log.info("Создан пользователь: id={}, name='{}'", savedUser.getId(), savedUser.getName());
        return mapper.toDto(savedUser);
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
        existingUser.setPassword(userDto.getPassword());
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

    @Transactional(readOnly = true)
    public boolean existsByEmail(String email) {
        return repository.getUserByEmail(email) != null;
    }

    @Transactional(readOnly = true)
    public boolean existsByUserName(String userName) {
        return repository.getUserByName(userName) != null;
    }


}
