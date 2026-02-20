package ru.mngerasimenko.todolist.view;

import com.vaadin.flow.component.AttachEvent;
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
import com.vaadin.flow.component.notification.Notification;
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
import ru.mngerasimenko.todolist.security.VaadinSecurityService;
import ru.mngerasimenko.todolist.service.TaskListService;
import ru.mngerasimenko.todolist.service.TodoService;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@PermitAll
@Route(value = "", layout = MainView.class)
@PageTitle("Список задач")
public class ListView extends VerticalLayout {
    private final VaadinSecurityService vaadinSecurityService;
    private final TodoService todoService;
    private final TaskListService taskListService;
    private final Grid<TodoDto> grid = new Grid<>(TodoDto.class);
    private final TextField filterText = new TextField();
    private final Span todoCountLabel = new Span();
    private TodoForm form;
    private UserDto authenticatedUser;
    private Div emptyState;

    /** ID текущего выбранного списка */
    private Long currentListId;

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    public ListView(TodoService todoService,
                    VaadinSecurityService vaadinSecurityService,
                    TaskListService taskListService) {
        this.todoService = todoService;
        this.vaadinSecurityService = vaadinSecurityService;
        this.taskListService = taskListService;

        init();
    }

    private void init() {
        authenticatedUser = vaadinSecurityService.getAuthenticatedUser();
        addClassName("list-view");
        setSizeFull();
        configureGrid();
        configureForm();
        configureEmptyState();

        add(getToolBar(), getContent());
        closeEditor();
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        // Находим MainView в иерархии компонентов и подписываемся на смену списка
        findMainView().ifPresent(mainView -> {
            currentListId = mainView.getSelectedListId();
            mainView.addListChangeListener(this::onListChanged);
            updateList();
        });
    }

    /**
     * Ищет MainView в родительской иерархии компонентов.
     */
    private java.util.Optional<MainView> findMainView() {
        Component current = this;
        while (current != null) {
            if (current instanceof MainView mainView) {
                return java.util.Optional.of(mainView);
            }
            current = current.getParent().orElse(null);
        }
        return java.util.Optional.empty();
    }

    /**
     * Вызывается при переключении списка в MainView.
     */
    private void onListChanged(Long newListId) {
        currentListId = newListId;
        updateList();
        closeEditor();
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
        List<TodoDto> items;

        if (currentListId != null) {
            // Загружаем задачи текущего списка (с учётом приватности)
            items = taskListService.getTodosByList(currentListId, authenticatedUser.getId());
        } else {
            items = List.of();
        }

        // Клиентская фильтрация по тексту
        String filter = filterText.getValue();
        if (filter != null && !filter.isBlank()) {
            String lowerFilter = filter.toLowerCase();
            items = items.stream()
                    .filter(t -> t.getName() != null
                            && t.getName().toLowerCase().contains(lowerFilter))
                    .collect(Collectors.toList());
        }

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

        // Колонка: Цвет создателя
        grid.addColumn(new ComponentRenderer<>(todo -> {
            Div colorDot = new Div();
            colorDot.addClassName("color-indicator");
            String color = todo.getCreatorColor();
            if (color != null && !color.isBlank()) {
                colorDot.getStyle().set("background-color", color);
            } else {
                colorDot.getStyle().set("background-color", "var(--lumo-contrast-20pct)");
            }
            return colorDot;
        })).setHeader("").setWidth("40px").setFlexGrow(0);

        // Колонка: Название задачи + иконка замка для приватных
        grid.addColumn(new ComponentRenderer<>(todo -> {
            HorizontalLayout nameLayout = new HorizontalLayout();
            nameLayout.setAlignItems(FlexComponent.Alignment.CENTER);
            nameLayout.setSpacing(true);
            nameLayout.getStyle().set("gap", "var(--lumo-space-xs)");

            Span nameSpan = new Span(todo.getName());
            if (todo.isDone()) {
                nameSpan.getStyle()
                        .set("text-decoration", "line-through")
                        .set("color", "var(--lumo-secondary-text-color)");
            } else {
                nameSpan.getStyle().set("font-weight", "500");
            }
            nameLayout.add(nameSpan);

            // Иконка замка для приватных задач
            if (todo.isPrivate()) {
                Icon lockIcon = VaadinIcon.LOCK.create();
                lockIcon.setSize("14px");
                lockIcon.addClassName("private-icon");
                nameLayout.add(lockIcon);
            }

            return nameLayout;
        })).setHeader("Задача").setFlexGrow(1);

        // Колонка: Автор
        grid.addColumn(new ComponentRenderer<>(todo -> {
            Span authorSpan = new Span(
                    todo.getUserName() != null ? todo.getUserName() : "");
            authorSpan.getStyle()
                    .set("color", "var(--lumo-secondary-text-color)")
                    .set("font-size", "var(--lumo-font-size-s)");
            return authorSpan;
        })).setHeader("Автор").setWidth("120px").setFlexGrow(0);

        // Колонка: Завершил
        grid.addColumn(new ComponentRenderer<>(todo -> {
            if (todo.isDone() && todo.getCompletorUserName() != null) {
                HorizontalLayout completorLayout = new HorizontalLayout();
                completorLayout.setAlignItems(FlexComponent.Alignment.CENTER);
                completorLayout.setSpacing(true);
                completorLayout.getStyle().set("gap", "var(--lumo-space-xs)");

                // Цветной индикатор завершившего
                if (todo.getCompletorColor() != null && !todo.getCompletorColor().isBlank()) {
                    Div dot = new Div();
                    dot.addClassName("color-indicator");
                    dot.getStyle().set("background-color", todo.getCompletorColor());
                    completorLayout.add(dot);
                }

                Span name = new Span(todo.getCompletorUserName());
                name.getStyle()
                        .set("color", "var(--lumo-secondary-text-color)")
                        .set("font-size", "var(--lumo-font-size-s)");
                completorLayout.add(name);
                return completorLayout;
            }
            return new Span("");
        })).setHeader("Завершил").setWidth("140px").setFlexGrow(0);

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
        if (currentListId == null) {
            Notification.show("Сначала выберите или создайте список задач",
                    4000, Notification.Position.MIDDLE);
            return;
        }
        grid.asSingleSelect().clear();
        TodoDto newTodo = new TodoDto(authenticatedUser.getId());
        newTodo.setListId(currentListId);
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
        form = new TodoForm(List.of());
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
