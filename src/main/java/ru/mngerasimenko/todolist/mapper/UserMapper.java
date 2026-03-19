package ru.mngerasimenko.todolist.mapper;

import org.springframework.stereotype.Component;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.dto.UserRequest;
import ru.mngerasimenko.todolist.dto.UserResponse;
import ru.mngerasimenko.todolist.model.User;

/**
 * Маппер для конвертации между User, UserDto и UserResponse.
 */
@Component
public class UserMapper {

    /**
     * Конвертирует сущность User в UserDto.
     */
    public UserDto toDto(User user) {
        if (user == null) {
            return null;
        }
        return UserDto.builder()
                .id(user.getId())
                .authId(user.getAuthId())
                .email(user.getEmail())
                .password(user.getPassword())
                .name(user.getName())
                .createdTaskColor(user.getCreatedTaskColor())
                .completedTaskColor(user.getCompletedTaskColor())
                .emailVerified(user.isEmailVerified())
                .createdAt(user.getCreatedAt())
                .subscriptionType(user.getSubscriptionType())
                .subscriptionExpiresAt(user.getSubscriptionExpiresAt())
                .betaTester(user.isBetaTester())
                .build();
    }

    /**
     * Конвертирует UserRequest в UserDto.
     */
    public UserDto toDto(UserRequest request) {
        if (request == null) {
            return null;
        }
        return UserDto.builder()
                .id(request.getId())
                .authId(request.getAuthId())
                .email(request.getEmail())
                .password(request.getPassword())
                .name(request.getName())
                .build();
    }

    /**
     * Конвертирует UserDto в сущность User.
     */
    public User toEntity(UserDto userDto) {
        if (userDto == null) {
            return null;
        }
        return new User(
                null,
                userDto.getAuthId(),
                userDto.getEmail(),
                userDto.getPassword(),
                userDto.getName()
        );
    }

    /**
     * Конвертирует UserDto в UserResponse.
     */
    public UserResponse toResponse(UserDto dto) {
        if (dto == null) {
            return null;
        }
        return UserResponse.builder()
                .id(dto.getId())
                .authId(dto.getAuthId())
                .email(dto.getEmail())
                .name(dto.getName())
                .createdTaskColor(dto.getCreatedTaskColor())
                .completedTaskColor(dto.getCompletedTaskColor())
                .emailVerified(dto.getEmailVerified())
                .createdAt(dto.getCreatedAt())
                .subscriptionType(dto.getSubscriptionType())
                .subscriptionExpiresAt(dto.getSubscriptionExpiresAt())
                .betaTester(dto.getBetaTester())
                .build();
    }
}
