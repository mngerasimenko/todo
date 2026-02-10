package ru.mngerasimenko.todolist.mapper;

import org.springframework.stereotype.Component;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.dto.UserRequest;
import ru.mngerasimenko.todolist.dto.UserResponse;
import ru.mngerasimenko.todolist.model.User;

@Component
public class UserMapper {

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
                .build();
    }

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

    public UserResponse toResponse(UserDto dto) {
        if (dto == null) {
            return null;
        }
        return UserResponse.builder()
                .id(dto.getId())
                .email(dto.getEmail())
                .name(dto.getName())
                .build();
    }
}
