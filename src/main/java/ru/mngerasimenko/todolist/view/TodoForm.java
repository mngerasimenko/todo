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
import java.util.List;
import ru.mngerasimenko.todolist.model.Todo;

public class TodoForm extends FormLayout {
    Todo todo;
    TextField title = new TextField("Todo title");

    Button save = new Button("Save");
    Button delete = new Button("Delete");
    Button close = new Button("Close");

    public TodoForm(List<Todo> todoList) {
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
        delete.addClickListener(event -> fireEvent(new DeleteEvent(this, todo)));
        close.addClickListener(event -> fireEvent(new CloseEvent(this)));

        return new HorizontalLayout(save, delete, close);
    }

    private void validateAndSave() {
        todo.setTitle(title.getValue());
        fireEvent(new SaveEvent(this, todo));
    }

    public void setTodo(Todo todo) {
        this.todo = todo;
        if (todo != null && todo.getTitle() != null) {
            this.title.setValue(todo.getTitle());
        } else {
            this.title.clear();
        }
    }

    public static abstract class TodoFormEvent extends ComponentEvent<TodoForm> {
        private final Todo todo;

        protected TodoFormEvent(TodoForm source, Todo todo) {
            super(source, false);
            this.todo = todo;
        }

        public Todo getTodo() {
            return todo;
        }
    }

    public static class SaveEvent extends TodoFormEvent {
        SaveEvent(TodoForm source, Todo todo) {
            super(source, todo);
        }
    }

    public static class DeleteEvent extends TodoFormEvent {
        DeleteEvent(TodoForm source, Todo todo) {
            super(source, todo);
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
