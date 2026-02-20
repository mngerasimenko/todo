package ru.mngerasimenko.todolist.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mngerasimenko.todolist.dto.TodoDto;
import ru.mngerasimenko.todolist.dto.list.ListMemberResponse;
import ru.mngerasimenko.todolist.dto.list.ListResponse;
import ru.mngerasimenko.todolist.exception.UserNotFoundException;
import ru.mngerasimenko.todolist.mapper.TaskListMapper;
import ru.mngerasimenko.todolist.mapper.TodoMapper;
import ru.mngerasimenko.todolist.model.TaskList;
import ru.mngerasimenko.todolist.model.TaskListRole;
import ru.mngerasimenko.todolist.model.TaskListUser;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.TaskListRepository;
import ru.mngerasimenko.todolist.repository.TaskListUserRepository;
import ru.mngerasimenko.todolist.repository.TodoRepository;
import ru.mngerasimenko.todolist.repository.UserRepository;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskListServiceImpl implements TaskListService {

    private final TaskListRepository taskListRepository;
    private final TaskListUserRepository taskListUserRepository;
    private final UserRepository userRepository;
    private final TodoRepository todoRepository;
    private final TaskListMapper taskListMapper;
    private final TodoMapper todoMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public ListResponse createList(String name, String password, Long creatorUserId) {
        if (taskListRepository.findByName(name).isPresent()) {
            throw new IllegalArgumentException("Список с названием '" + name + "' уже существует");
        }

        User creator = userRepository.findById(creatorUserId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + creatorUserId));

        TaskList taskList = new TaskList(name, passwordEncoder.encode(password));
        TaskList savedTaskList = taskListRepository.save(taskList);

        // Создатель получает роль ADMIN
        TaskListUser taskListUser = new TaskListUser(savedTaskList, creator, TaskListRole.ADMIN);
        taskListUserRepository.save(taskListUser);

        log.info("Создан список: id={}, name='{}', creatorId={}", savedTaskList.getId(), name, creatorUserId);
        return taskListMapper.toResponse(savedTaskList, TaskListRole.ADMIN);
    }

    @Override
    @Transactional
    public ListResponse joinList(String name, String password, Long userId) {
        TaskList taskList = taskListRepository.findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Список с названием '" + name + "' не найден"));

        if (!passwordEncoder.matches(password, taskList.getPasswordHash())) {
            throw new IllegalArgumentException("Неверный пароль списка");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + userId));

        // Проверяем — пользователь уже в списке?
        if (taskListUserRepository.existsByIdListIdAndIdUserId(taskList.getId(), userId)) {
            // Возвращаем текущую роль
            TaskListUser existing = taskListUserRepository.findByIdListIdAndIdUserId(taskList.getId(), userId)
                    .orElseThrow();
            log.info("Пользователь уже в списке: listId={}, userId={}", taskList.getId(), userId);
            return taskListMapper.toResponse(taskList, existing.getRole());
        }

        TaskListUser taskListUser = new TaskListUser(taskList, user, TaskListRole.USER);
        taskListUserRepository.save(taskListUser);

        log.info("Пользователь вступил в список: listId={}, userId={}", taskList.getId(), userId);
        return taskListMapper.toResponse(taskList, TaskListRole.USER);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ListResponse> getListsByUserId(Long userId) {
        List<TaskListUser> taskListUsers = taskListUserRepository.findByUserId(userId);
        return taskListUsers.stream()
                .map(tlu -> taskListMapper.toResponse(tlu.getTaskList(), tlu.getRole()))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ListMemberResponse> getMembers(Long listId, Long requestingUserId) {
        // Проверяем, что запрашивающий является участником списка
        if (!taskListUserRepository.existsByIdListIdAndIdUserId(listId, requestingUserId)) {
            throw new IllegalArgumentException("Вы не являетесь участником данного списка");
        }

        List<TaskListUser> members = taskListUserRepository.findByIdListId(listId);
        return members.stream()
                .map(taskListMapper::toMemberResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<TodoDto> getTodosByList(Long listId, Long requestingUserId) {
        // Проверяем, что запрашивающий является участником списка
        if (!taskListUserRepository.existsByIdListIdAndIdUserId(listId, requestingUserId)) {
            throw new IllegalArgumentException("Вы не являетесь участником данного списка");
        }

        // Возвращаем публичные + приватные задачи текущего пользователя
        return todoRepository.findByListIdVisibleToUser(listId, requestingUserId).stream()
                .map(todoMapper::toDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void leaveList(Long listId, Long userId) {
        if (!taskListUserRepository.existsByIdListIdAndIdUserId(listId, userId)) {
            throw new IllegalArgumentException("Вы не являетесь участником данного списка");
        }

        // Удаляем приватные задачи пользователя в этом списке
        todoRepository.deletePrivateTodosByListIdAndUserId(listId, userId);

        // Удаляем запись участия
        taskListUserRepository.deleteByListIdAndUserId(listId, userId);

        log.info("Пользователь вышел из списка: listId={}, userId={}", listId, userId);
    }
}
