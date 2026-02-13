package ru.mngerasimenko.todolist.mapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.dto.UserRequest;
import ru.mngerasimenko.todolist.dto.UserResponse;
import ru.mngerasimenko.todolist.model.User;

import static org.assertj.core.api.Assertions.assertThat;

class UserMapperTest {

    private UserMapper userMapper;

    @BeforeEach
    void setUp() {
        userMapper = new UserMapper();
    }

    @Test
    void toDto_WithValidUser_ReturnsUserDto() {
        User user = new User(
                1L,
                "auth-123",
                "test@mail.ru",
                "password123",
                "testuser"
        );

        UserDto result = userMapper.toDto(user);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getAuthId()).isEqualTo("auth-123");
        assertThat(result.getEmail()).isEqualTo("test@mail.ru");
        assertThat(result.getPassword()).isEqualTo("password123");
        assertThat(result.getName()).isEqualTo("testuser");
    }

    @Test
    void toDto_WithNullUser_ReturnsNull() {
        UserDto result = userMapper.toDto((User) null);

        assertThat(result).isNull();
    }

    @Test
    void toDto_WithPartialUserFields_ReturnsDtoWithSameFields() {
        User user = new User(
                2L,
                null,
                "user@mail.ru",
                null,
                "username"
        );

        UserDto result = userMapper.toDto(user);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(2L);
        assertThat(result.getAuthId()).isNull();
        assertThat(result.getEmail()).isEqualTo("user@mail.ru");
        assertThat(result.getPassword()).isNull();
        assertThat(result.getName()).isEqualTo("username");
    }

    @Test
    void toDto_WithValidUserRequest_ReturnsUserDto() {
        UserRequest request = new UserRequest();
        request.setId(1L);
        request.setAuthId("auth-456");
        request.setEmail("new@mail.ru");
        request.setPassword("newpass");
        request.setName("newuser");

        UserDto result = userMapper.toDto(request);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getAuthId()).isEqualTo("auth-456");
        assertThat(result.getEmail()).isEqualTo("new@mail.ru");
        assertThat(result.getPassword()).isEqualTo("newpass");
        assertThat(result.getName()).isEqualTo("newuser");
    }

    @Test
    void toDto_WithNullUserRequest_ReturnsNull() {
        UserDto result = userMapper.toDto((UserRequest) null);

        assertThat(result).isNull();
    }

    @Test
    void toDto_WithPartialUserRequestFields_ReturnsDtoWithSameFields() {
        UserRequest request = new UserRequest();
        request.setName("partial");

        UserDto result = userMapper.toDto(request);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("partial");
        assertThat(result.getId()).isNull();
        assertThat(result.getAuthId()).isNull();
        assertThat(result.getEmail()).isNull();
        assertThat(result.getPassword()).isNull();
    }


    @Test
    void toEntity_WithValidUserDto_ReturnsUserEntity() {
        UserDto userDto = UserDto.builder()
                .id(1L)
                .authId("auth-789")
                .email("entity@mail.ru")
                .password("entitypass")
                .name("entityuser")
                .build();

        User result = userMapper.toEntity(userDto);

        assertThat(result).isNotNull();

        assertThat(result.getAuthId()).isEqualTo("auth-789");
        assertThat(result.getEmail()).isEqualTo("entity@mail.ru");
        assertThat(result.getPassword()).isEqualTo("entitypass");
        assertThat(result.getName()).isEqualTo("entityuser");
    }

    @Test
    void toEntity_WithNullUserDto_ReturnsNull() {
        User result = userMapper.toEntity(null);

        assertThat(result).isNull();
    }

    @Test
    void toEntity_WithPartialUserDtoFields_ReturnsEntityWithSameFields() {
        UserDto userDto = UserDto.builder()
                .name("partial")
                .build();

        User result = userMapper.toEntity(userDto);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("partial");
        assertThat(result.getAuthId()).isNull();
        assertThat(result.getEmail()).isNull();
        assertThat(result.getPassword()).isNull();
    }

    @Test
    void toResponse_WithValidUserDto_ReturnsUserResponse() {
        UserDto userDto = UserDto.builder()
                .id(1L)
                .email("response@mail.ru")
                .name("responseuser")
                .build();

        UserResponse result = userMapper.toResponse(userDto);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getEmail()).isEqualTo("response@mail.ru");
        assertThat(result.getName()).isEqualTo("responseuser");
    }

    @Test
    void toResponse_WithNullUserDto_ReturnsNull() {
        UserResponse result = userMapper.toResponse(null);

        assertThat(result).isNull();
    }

    @Test
    void toResponse_WithPartialUserDtoFields_ReturnsResponseWithSameFields() {
        UserDto userDto = UserDto.builder()
                .name("partial")
                .build();

        UserResponse result = userMapper.toResponse(userDto);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("partial");
        assertThat(result.getId()).isNull();
        assertThat(result.getEmail()).isNull();
    }

    @Test
    void toDtoThenToEntity_MaintainsConsistency() {
        User originalUser = new User(
                1L,
                "auth-123",
                "test@mail.ru",
                "password123",
                "testuser"
        );

        UserDto dto = userMapper.toDto(originalUser);
        User convertedBack = userMapper.toEntity(dto);

        assertThat(convertedBack).isNotNull();
        assertThat(convertedBack.getAuthId()).isEqualTo(originalUser.getAuthId());
        assertThat(convertedBack.getEmail()).isEqualTo(originalUser.getEmail());
        assertThat(convertedBack.getPassword()).isEqualTo(originalUser.getPassword());
        assertThat(convertedBack.getName()).isEqualTo(originalUser.getName());
    }

    @Test
    void toDtoThenToResponse_MaintainsConsistency() {
        UserDto userDto = UserDto.builder()
                .id(1L)
                .email("test@mail.ru")
                .name("testuser")
                .build();

        UserResponse response = userMapper.toResponse(userDto);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(userDto.getId());
        assertThat(response.getEmail()).isEqualTo(userDto.getEmail());
        assertThat(response.getName()).isEqualTo(userDto.getName());
    }
}