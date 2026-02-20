package ru.mngerasimenko.todolist.view;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;
import ru.mngerasimenko.todolist.dto.TodoDto;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.dto.account.AccountResponse;
import ru.mngerasimenko.todolist.security.VaadinSecurityService;
import ru.mngerasimenko.todolist.service.AccountService;
import ru.mngerasimenko.todolist.service.TodoService;

import java.time.format.DateTimeFormatter;
import java.util.List;

@PermitAll
@Route(value = "", layout = MainView.class)
@PageTitle("Список задач")
public class ListView extends VerticalLayout {
    private final VaadinSecurityService vaadinSecurityService;
    private final TodoService todoService;
    private final AccountService accountService;
    private final Grid<TodoDto> grid = new Grid<>(TodoDto.class);
    private final TextField filterText = new TextField();
    private final Span todoCountLabel = new Span();
    private TodoForm form;
    private UserDto authenticatedUser;
    private Div emptyState;
    /** ID первого аккаунта пользователя — используется при создании задач через UI. */
    private Long defaultAccountId;

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    public ListView(TodoService todoService,
                    VaadinSecurityService vaadinSecurityService,
                    AccountService accountService) {
        this.todoService = todoService;
        this.vaadinSecurityService = vaadinSecurityService;
        this.accountService = accountService;

        init();
    }

    private void init() {
        authenticatedUser = vaadinSecurityService.getAuthenticatedUser();
        resolveDefaultAccount();
        addClassName("list-view");
        setSizeFull();
        configureGrid();
        configureForm();
        configureEmptyState();

        add(getToolBar(), getContent());
        updateList();
        closeEditor();
    }

    /**
     * Берём первый аккаунт пользователя — он будет использоваться при создании задач через UI.
     */
    private void resolveDefaultAccount() {
        List<AccountResponse> accounts = accountService.getAccountsByUserId(authenticatedUser.getId());
        if (!accounts.isEmpty()) {
            defaultAccountId = accounts.get(0).getId();
        }
    }

    private void configureEmptyState() {
        Icon emptyIcon = VaadinIcon.CHECK.create();
        emptyIcon.setSize("64px");
        emptyIcon.setColor("var(--lumo-tertiary-text-color)");
        emptyIcon.addClassName("empty-state-icon");

        H3 emptyTitle = new H3("Задач пока нет");
        emptyTitle.addClassName("empty-state-text");

        Paragraph emptyDescription = new Paragraph(
                "Нажмите кнопку \"Добавить задачу\", чтобы создать первую");
        emptyDescription.getStyle().set("color", "var(--lumo-tertiary-text-color)");

        emptyState = new Div(emptyIcon, emptyTitle, emptyDescription);
        emptyState.addClassName("empty-state");
        emptyState.setVisible(false);
    }

    private void closeEditor() {
        form.setTodo(null);
        form.setVisible(false);
        removeClassName("editing");
    }

    public void editTodo(TodoDto todoDto) {
        if (todoDto == null) {
            closeEditor();
        } else {
            form.setTodo(todoDto);
            form.setVisible(true);
            addClassName("editing");
        }
    }

    private void updateList() {
        List<TodoDto> items = todoService.getFilteredTodosByUserId(
                authenticatedUser.getId(), filterText.getValue());
        grid.setItems(items);

        // Счётчик задач
        long total = items.size();
        long done = items.stream().filter(TodoDto::isDone).count();
        todoCountLabel.setText(done + " из " + total + " выполнено");

        // Пустое состояние
        boolean isEmpty = items.isEmpty();
        emptyState.setVisible(isEmpty);
        grid.setVisible(!isEmpty);
    }

    private void configureGrid() {
        grid.addClassNames("todo-grid");
        grid.setSizeFull();
        grid.removeAllColumns();

        // Колонка: Статус (иконка)
        grid.addColumn(new ComponentRenderer<>(todo -> {
            Icon icon;
            if (todo.isDone()) {
                icon = VaadinIcon.CHECK_CIRCLE.create();
                icon.setColor("var(--lumo-success-color)");
            } else {
                icon = VaadinIcon.CIRCLE_THIN.create();
                icon.setColor("var(--lumo-tertiary-text-color)");
            }
            icon.setSize("20px");
            icon.getStyle().set("cursor", "pointer");
            icon.addClickListener(e -> {
                if (todo.isDone()) {
                    todoService.markAsUndone(todo.getId());
                } else {
                    todoService.markAsDone(todo.getId());
                }
                updateList();
            });
            return icon;
        })).setHeader("").setWidth("60px").setFlexGrow(0);

        // Колонка: Название задачи
        grid.addColumn(new ComponentRenderer<>(todo -> {
            Span nameSpan = new Span(todo.getName());
            if (todo.isDone()) {
                nameSpan.getStyle()
                        .set("text-decoration", "line-through")
                        .set("color", "var(--lumo-secondary-text-color)");
            } else {
                nameSpan.getStyle().set("font-weight", "500");
            }
            return nameSpan;
        })).setHeader("Задача").setFlexGrow(1);

        // Колонка: Дата
        grid.addColumn(new ComponentRenderer<>(todo -> {
            Span dateSpan = new Span(
                    todo.getCreatedAt() != null
                            ? todo.getCreatedAt().format(DATE_FORMATTER)
                            : "");
            dateSpan.getStyle()
                    .set("color", "var(--lumo-secondary-text-color)")
                    .set("font-size", "var(--lumo-font-size-s)");
            return dateSpan;
        })).setHeader("Дата").setWidth("150px").setFlexGrow(0);

        // Варианты оформления
        grid.addThemeVariants(GridVariant.LUMO_NO_BORDER, GridVariant.LUMO_ROW_STRIPES);

        grid.asSingleSelect().addValueChangeListener(event -> editTodo(event.getValue()));
        grid.setClassNameGenerator(this::getRowClassName);
    }

    private String getRowClassName(TodoDto todoDto) {
        if (todoDto.isDone()) {
            return "todo-row-done";
        }
        return "todo-row-active";
    }

    private HorizontalLayout getToolBar() {
        // Поле фильтра с иконкой поиска
        filterText.setPlaceholder("Поиск задач...");
        filterText.setClearButtonVisible(true);
        filterText.setValueChangeMode(ValueChangeMode.LAZY);
        filterText.addValueChangeListener(e -> updateList());
        filterText.setPrefixComponent(VaadinIcon.SEARCH.create());

        // Кнопка добавления
        Button addTodoButton = new Button("Добавить задачу", new Icon(VaadinIcon.PLUS));
        addTodoButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        addTodoButton.addClassName("add-todo-btn");
        addTodoButton.addClickListener(click -> addTodo());

        // Счётчик задач
        todoCountLabel.getStyle()
                .set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-s)")
                .set("white-space", "nowrap");

        HorizontalLayout toolbar = new HorizontalLayout(filterText, todoCountLabel, addTodoButton);
        toolbar.addClassName("toolbar");
        toolbar.setAlignItems(FlexComponent.Alignment.CENTER);
        toolbar.setWidthFull();
        toolbar.expand(filterText);

        return toolbar;
    }

    private void addTodo() {
        if (defaultAccountId == null) {
            com.vaadin.flow.component.notification.Notification
                    .show("Сначала вступите в аккаунт через мобильное приложение",
                            4000,
                            com.vaadin.flow.component.notification.Notification.Position.MIDDLE);
            return;
        }
        grid.asSingleSelect().clear();
        TodoDto newTodo = new TodoDto(authenticatedUser.getId());
        newTodo.setAccountId(defaultAccountId);
        editTodo(newTodo);
    }

    private Component getContent() {
        VerticalLayout content = new VerticalLayout(form, emptyState, grid);
        content.setFlexGrow(2, grid);
        content.setFlexGrow(1, form);
        content.addClassNames("content");
        content.setSizeFull();
        return content;
    }

    private void configureForm() {
        form = new TodoForm(todoService.getTodosByUserId(authenticatedUser.getId()));
        form.setWidth("25em");
        form.addSaveListener(this::saveTodo);
        form.addDeleteListener(this::deleteTodo);
        form.addCloseListener(e -> closeEditor());
    }

    private void saveTodo(TodoForm.SaveEvent event) {
        TodoDto todoDto = event.getTodoDto();
        if (todoDto.getId() == null) {
            todoService.createTodo(todoDto);
        } else {
            todoService.updateTodo(todoDto.getId(), todoDto);
        }
        updateList();
        closeEditor();
    }

    private void deleteTodo(TodoForm.DeleteEvent event) {
        TodoDto todoDto = event.getTodoDto();
        todoService.deleteTodo(todoDto.getId());
        updateList();
        closeEditor();
    }

}
