package ru.mngerasimenko.todolist.service;

import ru.mngerasimenko.todolist.dto.TodoDto;

import java.util.List;

/**
 * Сервис управления задачами.
 * Предоставляет CRUD-операции, фильтрацию и управление статусом выполнения.
 */
public interface TodoService {

    /** Создаёт новую задачу */
    TodoDto createTodo(TodoDto todoDto);

    /** Обновляет существующую задачу по ID */
    TodoDto updateTodo(Long id, TodoDto todoDto);

    /** Возвращает задачу по ID */
    TodoDto getTodoById(Long id);

    /** Возвращает все задачи */
    List<TodoDto> getAllTodos();

    /** Возвращает все задачи пользователя */
    List<TodoDto> getTodosByUserId(Long userId);

    /** Возвращает активные (невыполненные) задачи пользователя */
    List<TodoDto> getActiveTodosByUserId(Long userId);

    /** Возвращает выполненные задачи пользователя */
    List<TodoDto> getCompletedTodosByUserId(Long userId);

    /** Удаляет задачу по ID */
    void deleteTodo(Long id);

    /** Удаляет все задачи пользователя */
    void deleteTodosByUserId(Long userId);

    /** Отмечает задачу как выполненную (без указания исполнителя) */
    TodoDto markAsDone(Long id);

    /** Отмечает задачу как выполненную с указанием исполнителя */
    TodoDto markAsDone(Long id, Long completorUserId);

    /** Снимает отметку выполнения задачи */
    TodoDto markAsUndone(Long id);

    /** Возвращает задачи пользователя с фильтрацией по имени */
    List<TodoDto> getFilteredTodosByUserId(Long id, String filter);
}
