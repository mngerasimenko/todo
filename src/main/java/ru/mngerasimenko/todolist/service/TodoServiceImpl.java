package ru.mngerasimenko.todolist.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mngerasimenko.todolist.dto.TodoDto;
import ru.mngerasimenko.todolist.exception.ListNotFoundException;
import ru.mngerasimenko.todolist.exception.TodoNotFoundException;
import ru.mngerasimenko.todolist.exception.UserNotFoundException;
import ru.mngerasimenko.todolist.mapper.TodoMapper;
import ru.mngerasimenko.todolist.model.TaskList;
import ru.mngerasimenko.todolist.model.Todo;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.TaskListRepository;
import ru.mngerasimenko.todolist.repository.TaskListUserRepository;
import ru.mngerasimenko.todolist.repository.TodoRepository;
import ru.mngerasimenko.todolist.repository.UserRepository;

import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TodoServiceImpl implements TodoService {

    private final TodoRepository todoRepository;
    private final UserRepository userRepository;
    private final TaskListRepository taskListRepository;
    private final TaskListUserRepository taskListUserRepository;
    private final TodoMapper todoMapper;
    private final SubscriptionService subscriptionService;

    @Override
    @Transactional
    public TodoDto createTodo(TodoDto todoDto) {
        User user = userRepository.findById(todoDto.getUserId())
                .orElseThrow(() -> new UserNotFoundException(
                        "User not found with id: " + todoDto.getUserId()));

        TaskList taskList = taskListRepository.findById(todoDto.getListId())
                .orElseThrow(() -> new ListNotFoundException(
                        "Список не найден. Возможно, он был удалён"));

        if (!taskListUserRepository.existsByIdListIdAndIdUserId(todoDto.getListId(), todoDto.getUserId())) {
            throw new IllegalArgumentException("Пользователь не является участником данного списка");
        }

        subscriptionService.assertCanCreateTodo(todoDto.getListId(), todoDto.getUserId());
        if (todoDto.isPrivate()) {
            subscriptionService.assertCanCreatePrivateTodo(todoDto.getUserId());
        }

        todoDto.setDone(false);
        todoDto.setUserId(user.getId());
        todoDto.setCreatedAt(LocalDateTime.now());
        Todo todo = todoMapper.toEntity(todoDto);
        todo.setUser(user);
        todo.setTaskList(taskList);

        Todo savedTodo = todoRepository.save(todo);
        log.info("Создана задача: id={}, name='{}', userId={}, listId={}, private={}",
                savedTodo.getId(), savedTodo.getName(), user.getId(), taskList.getId(), savedTodo.getIsPrivate());
        return todoMapper.toDto(savedTodo);
    }

    @Override
    @Transactional
    public TodoDto updateTodo(Long id, TodoDto todoDto, Long requestingUserId) {
        Todo existingTodo = todoRepository.findById(id)
                .orElseThrow(() -> new TodoNotFoundException("Todo not found with id: " + id));
        assertUserIsMember(existingTodo, requestingUserId);

        if (todoDto.getUserId() != null && !todoDto.getUserId().equals(existingTodo.getUserId())) {
            User newUser = userRepository.findById(todoDto.getUserId())
                    .orElseThrow(() -> new UserNotFoundException(
                            "User not found with id: " + todoDto.getUserId()));
            existingTodo.setUser(newUser);
        }

        boolean wasDone = Boolean.TRUE.equals(existingTodo.isDone());
        boolean nowDone = todoDto.isDone();

        log.debug("updateTodo: входной done={}, существующий done={}", nowDone, wasDone);
        todoMapper.updateEntityFromDto(todoDto, existingTodo);

        // Логика completedAt и completorUser
        if (!wasDone && nowDone) {
            // Задача выполнена: проставляем время выполнения и исполнителя
            existingTodo.setCompletedAt(LocalDateTime.now());
            if (todoDto.getCompletorUserId() != null) {
                User completor = userRepository.findById(todoDto.getCompletorUserId())
                        .orElse(null);
                existingTodo.setCompletorUser(completor);
            }
        } else if (wasDone && !nowDone) {
            // Задача снята с выполнения: очищаем completedAt и completorUser
            existingTodo.setCompletedAt(null);
            existingTodo.setCompletorUser(null);
        }

        Todo updatedTodo = todoRepository.save(existingTodo);
        log.info("Обновлена задача: id={}, name='{}', done={}, completedAt={}",
                updatedTodo.getId(), updatedTodo.getName(), updatedTodo.isDone(), updatedTodo.getCompletedAt());
        return todoMapper.toDto(updatedTodo);
    }

    @Override
    @Transactional(readOnly = true)
    public TodoDto getTodoById(Long id, Long requestingUserId) {
        Todo todo = todoRepository.findById(id)
                .orElseThrow(() -> new TodoNotFoundException("Todo not found with id: " + id));
        assertUserIsMember(todo, requestingUserId);
        return todoMapper.toDto(todo);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TodoDto> getAllTodos(Long requestingUserId) {
        List<Long> listIds = taskListUserRepository.findListIdsByUserId(requestingUserId);
        if (listIds.isEmpty()) {
            return List.of();
        }
        return todoRepository.findByListIdsVisibleToUser(listIds, requestingUserId).stream()
                .map(todoMapper::toDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TodoDto> getFilteredTodosByUserId(Long userId, String filter) {
        return todoRepository.findAllByUserIdAndNameContainingIgnoreCase(userId, filter).stream()
                .map(todoMapper::toDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TodoDto> getTodosByUserId(Long userId, Long requestingUserId) {
        List<Todo> todos = todoRepository.findByUserId(userId);
        log.debug("Загрузка задач для userId={}, requestingUserId={}, найдено: {}",
                userId, requestingUserId, todos.size());
        // Фильтрация: приватные задачи видны только их создателю
        if (!userId.equals(requestingUserId)) {
            todos = todos.stream()
                    .filter(todo -> !Boolean.TRUE.equals(todo.getIsPrivate()))
                    .toList();
        }
        return todos.stream()
                .map(todoMapper::toDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TodoDto> getActiveTodosByUserId(Long userId) {
        return todoRepository.findByUserIdAndDone(userId, false).stream()
                .map(todoMapper::toDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TodoDto> getCompletedTodosByUserId(Long userId) {
        return todoRepository.findByUserIdAndDone(userId, true).stream()
                .map(todoMapper::toDto)
                .toList();
    }

    @Override
    @Transactional
    public void deleteTodo(Long id, Long requestingUserId) {
        Todo todo = todoRepository.findById(id)
                .orElseThrow(() -> new TodoNotFoundException("Todo not found with id: " + id));
        assertUserIsMember(todo, requestingUserId);
        todoRepository.deleteById(id);
        log.info("Удалена задача: id={}, userId={}", id, requestingUserId);
    }

    @Override
    @Transactional
    public void deleteTodosByUserId(Long userId) {
        todoRepository.deleteByUserId(userId);
    }

    @Override
    @Transactional
    public TodoDto markAsDone(Long id, Long completorUserId) {
        Todo todo = todoRepository.findById(id)
                .orElseThrow(() -> new TodoNotFoundException("Todo not found with id: " + id));
        assertUserIsMember(todo, completorUserId);
        todo.setDone(true);
        todo.setCompletedAt(LocalDateTime.now());
        if (completorUserId != null) {
            User completor = userRepository.findById(completorUserId)
                    .orElse(null);
            todo.setCompletorUser(completor);
        }
        Todo updatedTodo = todoRepository.save(todo);
        return todoMapper.toDto(updatedTodo);
    }

    @Override
    @Transactional
    public TodoDto markAsUndone(Long id, Long requestingUserId) {
        Todo todo = todoRepository.findById(id)
                .orElseThrow(() -> new TodoNotFoundException("Todo not found with id: " + id));
        assertUserIsMember(todo, requestingUserId);
        todo.setDone(false);
        todo.setCompletedAt(null);
        todo.setCompletorUser(null);
        Todo updatedTodo = todoRepository.save(todo);
        return todoMapper.toDto(updatedTodo);
    }

    /**
     * Проверяет, что пользователь является участником списка, к которому принадлежит задача.
     */
    private void assertUserIsMember(Todo todo, Long userId) {
        Long listId = todo.getTaskList().getId();
        if (!taskListUserRepository.existsByIdListIdAndIdUserId(listId, userId)) {
            throw new AccessDeniedException(
                    "Доступ запрещён: пользователь не является участником списка задачи");
        }
    }
}
