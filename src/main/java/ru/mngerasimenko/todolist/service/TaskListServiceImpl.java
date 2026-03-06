package ru.mngerasimenko.todolist.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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
    @Transactional(noRollbackFor = DataIntegrityViolationException.class)
    public ListResponse createList(String name, String password, Long creatorUserId) {
        User creator = userRepository.findById(creatorUserId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + creatorUserId));

        TaskList taskList = new TaskList(name, passwordEncoder.encode(password));

        TaskList savedTaskList;
        try {
            savedTaskList = taskListRepository.saveAndFlush(taskList);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Список с названием '" + name + "' уже существует");
        }

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

        java.util.Optional<TaskListUser> existing =
                taskListUserRepository.findByIdListIdAndIdUserId(taskList.getId(), userId);
        if (existing.isPresent()) {
            log.info("Пользователь уже в списке: listId={}, userId={}", taskList.getId(), userId);
            return taskListMapper.toResponse(taskList, existing.get().getRole());
        }

        TaskListRole role = taskListUserRepository.existsByIdListIdAndRole(taskList.getId(), TaskListRole.ADMIN)
                ? TaskListRole.USER
                : TaskListRole.ADMIN;

        TaskListUser taskListUser = new TaskListUser(taskList, user, role);
        taskListUserRepository.save(taskListUser);

        log.info("Пользователь вступил в список: listId={}, userId={}, role={}", taskList.getId(), userId, role);
        return taskListMapper.toResponse(taskList, role);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ListResponse> getListsByUserId(Long userId) {
        List<TaskListUser> taskListUsers = taskListUserRepository.findByUserId(userId);
        return taskListUsers.stream()
                .map(tlu -> taskListMapper.toResponse(tlu.getTaskList(), tlu.getRole()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ListMemberResponse> getMembers(Long listId, Long requestingUserId) {
        if (!taskListUserRepository.existsByIdListIdAndIdUserId(listId, requestingUserId)) {
            throw new IllegalArgumentException("Вы не являетесь участником данного списка");
        }

        List<TaskListUser> members = taskListUserRepository.findByIdListId(listId);
        return members.stream()
                .map(taskListMapper::toMemberResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TodoDto> getTodosByList(Long listId, Long requestingUserId) {
        if (!taskListUserRepository.existsByIdListIdAndIdUserId(listId, requestingUserId)) {
            throw new IllegalArgumentException("Вы не являетесь участником данного списка");
        }

        return todoRepository.findByListIdVisibleToUser(listId, requestingUserId).stream()
                .map(todoMapper::toDto)
                .toList();
    }

    @Override
    @Transactional
    public void leaveList(Long listId, Long userId) {
        TaskListUser membership = taskListUserRepository.findByIdListIdAndIdUserId(listId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Вы не являетесь участником данного списка"));

        if (membership.getRole() == TaskListRole.ADMIN) {
            throw new IllegalArgumentException("Администратор не может покинуть список. Используйте удаление списка");
        }

        todoRepository.deletePrivateTodosByListIdAndUserId(listId, userId);
        taskListUserRepository.deleteByListIdAndUserId(listId, userId);

        log.info("Пользователь вышел из списка: listId={}, userId={}", listId, userId);
    }

    @Override
    @Transactional
    public void deleteList(Long listId, Long userId) {
        TaskListUser membership = taskListUserRepository.findByIdListIdAndIdUserId(listId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Вы не являетесь участником данного списка"));

        if (membership.getRole() != TaskListRole.ADMIN) {
            throw new IllegalArgumentException("Только администратор может удалить список");
        }

        todoRepository.deleteByListId(listId);
        taskListUserRepository.deleteByListId(listId);
        taskListRepository.deleteByListId(listId);

        log.info("Список удалён: listId={}, userId={}", listId, userId);
    }
}
