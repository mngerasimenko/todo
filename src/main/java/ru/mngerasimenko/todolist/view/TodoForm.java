package ru.mngerasimenko.todolist.view;

import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.shared.Registration;
import ru.mngerasimenko.todolist.dto.TodoDto;

import java.util.List;

public class TodoForm extends FormLayout {
    TodoDto todoDto;
    TextField title = new TextField("Todo title");
    Button save = new Button("Save");
    Button delete = new Button("Delete");
    Button close = new Button("Close");

    public TodoForm(List<TodoDto> todoList) {
        addClassName("todo-form");
        add(title, createButtonsLayout());
    }

    private HorizontalLayout createButtonsLayout() {
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        delete.addThemeVariants(ButtonVariant.LUMO_ERROR);
        close.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        save.addClickShortcut(Key.ENTER);
        close.addClickShortcut(Key.ESCAPE);

        save.addClickListener(event -> validateAndSave());
        delete.addClickListener(event -> fireEvent(new DeleteEvent(this, todoDto)));
        close.addClickListener(event -> fireEvent(new CloseEvent(this)));

        return new HorizontalLayout(save, delete, close);
    }

    private void validateAndSave() {
        todoDto.setName(title.getValue());
        fireEvent(new SaveEvent(this, todoDto));
    }

    public void setTodo(TodoDto todoDto) {
        this.todoDto = todoDto;
        if (todoDto != null && todoDto.getName() != null) {
            this.title.setValue(todoDto.getName());
        } else {
            this.title.clear();
        }
    }

    public static abstract class TodoFormEvent extends ComponentEvent<TodoForm> {
        private final TodoDto todoDto;

        protected TodoFormEvent(TodoForm source, TodoDto todoDto) {
            super(source, false);
            this.todoDto = todoDto;
        }

        public TodoDto getTodoDto() {
            return todoDto;
        }
    }

    public static class SaveEvent extends TodoFormEvent {
        SaveEvent(TodoForm source, TodoDto todoDto) {
            super(source, todoDto);
        }
    }

    public static class DeleteEvent extends TodoFormEvent {
        DeleteEvent(TodoForm source, TodoDto todoDto) {
            super(source, todoDto);
        }
    }

    public static class CloseEvent extends TodoFormEvent {
        CloseEvent(TodoForm source) {
            super(source, null);
        }
    }

    public Registration addDeleteListener(ComponentEventListener<DeleteEvent> listener) {
        return addListener(DeleteEvent.class, listener);
    }

    public Registration addSaveListener(ComponentEventListener<SaveEvent> listener) {
        return addListener(SaveEvent.class, listener);
    }

    public Registration addCloseListener(ComponentEventListener<CloseEvent> listener) {
        return addListener(CloseEvent.class, listener);
    }
}
