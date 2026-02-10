package ru.mngerasimenko.todolist.service;

import ru.mngerasimenko.todolist.dto.UserDto;

import java.util.List;

public interface UserService {

    List<UserDto> getAll();

    void delete(long id);

    UserDto getUserByUserName(String userName);

    UserDto getUserByAuthId(String authId);

    UserDto createUser(UserDto userDto);

    UserDto updateUser(Long id, UserDto userDto);

    UserDto getUserById(Long id);
}
