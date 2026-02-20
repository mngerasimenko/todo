package ru.mngerasimenko.todolist.mapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.mngerasimenko.todolist.dto.list.ListMemberResponse;
import ru.mngerasimenko.todolist.dto.list.ListResponse;
import ru.mngerasimenko.todolist.model.TaskList;
import ru.mngerasimenko.todolist.model.TaskListRole;
import ru.mngerasimenko.todolist.model.TaskListUser;
import ru.mngerasimenko.todolist.model.TaskListUserId;
import ru.mngerasimenko.todolist.model.User;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class TaskListMapperTest {

    private TaskListMapper taskListMapper;
    private TaskList testTaskList;
    private User testUser;
    private TaskListUser testTaskListUser;

    @BeforeEach
    void setUp() {
        taskListMapper = new TaskListMapper();

        testTaskList = new TaskList("TestList", "$2a$10$hash");
        testTaskList.setId(1L);
        testTaskList.setCreatedAt(LocalDateTime.of(2026, 1, 1, 12, 0));

        testUser = new User();
        testUser.setId(2L);
        testUser.setName("testuser");
        testUser.setEmail("test@mail.ru");
        testUser.setPassword("hash");

        testTaskListUser = new TaskListUser();
        testTaskListUser.setId(new TaskListUserId(1L, 2L));
        testTaskListUser.setTaskList(testTaskList);
        testTaskListUser.setUser(testUser);
        testTaskListUser.setRole(TaskListRole.USER);
        testTaskListUser.setJoinedAt(LocalDateTime.of(2026, 1, 2, 10, 0));
    }

    @Test
    void toResponse_WithValidTaskListAndRole_ReturnsListResponse() {
        ListResponse response = taskListMapper.toResponse(testTaskList, TaskListRole.ADMIN);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getName()).isEqualTo("TestList");
        assertThat(response.getRole()).isEqualTo("ADMIN");
        assertThat(response.getCreatedAt()).isNotNull();
    }

    @Test
    void toResponse_WithNullTaskList_ReturnsNull() {
        ListResponse response = taskListMapper.toResponse(null, TaskListRole.USER);

        assertThat(response).isNull();
    }

    @Test
    void toResponse_WithNullRole_ReturnsResponseWithNullRole() {
        ListResponse response = taskListMapper.toResponse(testTaskList, null);

        assertThat(response).isNotNull();
        assertThat(response.getRole()).isNull();
    }

    @Test
    void toResponse_WithNullCreatedAt_ReturnsResponseWithNullCreatedAt() {
        testTaskList.setCreatedAt(null);

        ListResponse response = taskListMapper.toResponse(testTaskList, TaskListRole.USER);

        assertThat(response).isNotNull();
        assertThat(response.getCreatedAt()).isNull();
    }

    @Test
    void toMemberResponse_WithValidTaskListUser_ReturnsMemberResponse() {
        ListMemberResponse response = taskListMapper.toMemberResponse(testTaskListUser);

        assertThat(response).isNotNull();
        assertThat(response.getUserId()).isEqualTo(2L);
        assertThat(response.getUserName()).isEqualTo("testuser");
        assertThat(response.getRole()).isEqualTo("USER");
        assertThat(response.getJoinedAt()).isNotNull();
    }

    @Test
    void toMemberResponse_WithNullTaskListUser_ReturnsNull() {
        ListMemberResponse response = taskListMapper.toMemberResponse(null);

        assertThat(response).isNull();
    }

    @Test
    void toMemberResponse_WithNullUser_ReturnsResponseWithNullUserFields() {
        testTaskListUser.setUser(null);

        ListMemberResponse response = taskListMapper.toMemberResponse(testTaskListUser);

        assertThat(response).isNotNull();
        assertThat(response.getUserId()).isNull();
        assertThat(response.getUserName()).isNull();
    }

    @Test
    void toMemberResponse_WithNullJoinedAt_ReturnsResponseWithNullJoinedAt() {
        testTaskListUser.setJoinedAt(null);

        ListMemberResponse response = taskListMapper.toMemberResponse(testTaskListUser);

        assertThat(response).isNotNull();
        assertThat(response.getJoinedAt()).isNull();
    }
}
