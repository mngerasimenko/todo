package ru.mngerasimenko.todolist.view;

import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.shared.Registration;
import ru.mngerasimenko.todolist.dto.TodoDto;

import java.time.format.DateTimeFormatter;
import java.util.List;

public class TodoForm extends FormLayout {
    TodoDto todoDto;

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    // Заголовок формы
    H3 formTitle = new H3("Новая задача");

    // Поля
    TextField title = new TextField("Название задачи");
    Checkbox doneCheckbox = new Checkbox("Выполнено");
    Checkbox privateCheckbox = new Checkbox("Приватная задача");
    Span dateLabel = new Span();

    // Кнопки
    Button save = new Button("Сохранить", new Icon(VaadinIcon.CHECK));
    Button delete = new Button("Удалить", new Icon(VaadinIcon.TRASH));
    Button close = new Button("Закрыть", new Icon(VaadinIcon.CLOSE));

    public TodoForm(List<TodoDto> todoList) {
        addClassName("todo-form");

        // Настройка полей
        title.setPlaceholder("Введите название задачи...");
        title.setWidthFull();
        title.setClearButtonVisible(true);
        title.setPrefixComponent(VaadinIcon.EDIT.create());

        doneCheckbox.getStyle()
                .set("padding-top", "var(--lumo-space-s)");

        privateCheckbox.getStyle()
                .set("padding-top", "var(--lumo-space-xs)");
        privateCheckbox.getElement().setAttribute("title",
                "Приватные задачи видны только вам");

        dateLabel.getStyle()
                .set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-s)")
                .set("padding", "var(--lumo-space-xs) 0");

        formTitle.getStyle()
                .set("margin", "0 0 var(--lumo-space-s) 0")
                .set("color", "var(--lumo-primary-text-color)");

        add(formTitle, title, doneCheckbox, privateCheckbox, dateLabel, createButtonsLayout());
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

        HorizontalLayout buttonsLayout = new HorizontalLayout(save, delete, close);
        buttonsLayout.addClassName("todo-form-buttons");
        buttonsLayout.setWidthFull();
        buttonsLayout.setAlignItems(FlexComponent.Alignment.CENTER);
        return buttonsLayout;
    }

    private void validateAndSave() {
        todoDto.setName(title.getValue());
        todoDto.setDone(doneCheckbox.getValue());
        todoDto.setIsPrivate(privateCheckbox.getValue());
        fireEvent(new SaveEvent(this, todoDto));
    }

    public void setTodo(TodoDto todoDto) {
        this.todoDto = todoDto;
        if (todoDto != null) {
            // Название
            if (todoDto.getName() != null) {
                this.title.setValue(todoDto.getName());
            } else {
                this.title.clear();
            }

            // Чекбокс статуса
            doneCheckbox.setValue(todoDto.isDone());

            // Дата
            if (todoDto.getCreatedAt() != null) {
                dateLabel.setText("Создано: " + todoDto.getCreatedAt().format(DATE_FORMATTER));
                dateLabel.setVisible(true);
            } else {
                dateLabel.setVisible(false);
            }

            // Приватность
            privateCheckbox.setValue(todoDto.isPrivate());

            // Контекстный заголовок и видимость элементов
            if (todoDto.getId() == null) {
                formTitle.setText("Новая задача");
                delete.setVisible(false);
                doneCheckbox.setVisible(false);
                dateLabel.setVisible(false);
                privateCheckbox.setVisible(true);
                privateCheckbox.setReadOnly(false);
            } else {
                formTitle.setText("Редактирование задачи");
                delete.setVisible(true);
                doneCheckbox.setVisible(true);
                privateCheckbox.setVisible(true);
                privateCheckbox.setReadOnly(true);
            }
        } else {
            this.title.clear();
            doneCheckbox.setValue(false);
            privateCheckbox.setValue(false);
            dateLabel.setText("");
        }
    }

    // === События ===

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
