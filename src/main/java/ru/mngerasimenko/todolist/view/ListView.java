package ru.mngerasimenko.todolist.view;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;
import ru.mngerasimenko.todolist.model.Todo;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.security.SecurityService;
import ru.mngerasimenko.todolist.service.TodoService;

@PermitAll
@Route(value = "", layout = MainView.class)
@PageTitle("Todo list")
public class ListView extends VerticalLayout {
    private final SecurityService securityService;
    private final TodoService todoService;
    private final Grid<Todo> grid = new Grid<>(Todo.class);
    private final TextField filterText = new TextField();
    private TodoForm form;
    private User authenticatedUser;

    public ListView(TodoService todoService, SecurityService securityService) {
        this.todoService = todoService;
        this.securityService = securityService;

        init();
    }

    private void init() {
        authenticatedUser = securityService.getAuthenticatedUser();
        addClassName("list-view");
        setSizeFull();
        configureGrid();
        configureForm();

        add(getToolBar(), getContent());
        updateList();
        closeEditor();
    }

    private void closeEditor() {
        form.setTodo(null);
        form.setVisible(false);
        removeClassName("editing");
    }

    public void editTodo(Todo todo) {
        if (todo == null) {
            closeEditor();
        } else {
            form.setTodo(todo);
            form.setVisible(true);
            addClassName("editing");
        }
    }

    private void updateList() {
        grid.setItems(todoService.getAllByFilter(authenticatedUser.getId(), filterText.getValue()));
    }

    private void configureGrid() {
        grid.addClassNames("todo-grid");
        grid.setSizeFull();
        grid.setColumns("title");
        grid.getColumns().forEach(col -> col.setAutoWidth(true));
        grid.asSingleSelect().addValueChangeListener(event -> editTodo(event.getValue()));
    }

    private HorizontalLayout getToolBar() {
        filterText.setPlaceholder("Filter by todo name...");
        filterText.setClearButtonVisible(true);
        filterText.setValueChangeMode(ValueChangeMode.LAZY);
        filterText.addValueChangeListener(e -> updateList());

        Button addTodoButton = new Button("Add todo");
        addTodoButton.addClickListener(click -> addTodo());
        
        HorizontalLayout toolbar = new HorizontalLayout(filterText, addTodoButton);
        toolbar.addClassName("toolbar");

        return toolbar;
    }

    private void addTodo() {
        grid.asSingleSelect().clear();
        editTodo(new Todo(authenticatedUser.getId()));
    }

    private Component getContent() {
        VerticalLayout content = new VerticalLayout(form, grid);
        content.setFlexGrow(2, grid);
        content.setFlexGrow(1, form);
        content.addClassNames("content");
        content.setSizeFull();
        return content;
    }

    private void configureForm() {
        form = new TodoForm(todoService.getAll(1));
        form.setWidth("25em");
        form.addSaveListener(this::saveTodo);
        form.addDeleteListener(this::deleteTodo);
        form.addCloseListener(e -> closeEditor());
    }

    private void saveTodo(TodoForm.SaveEvent event) {
        todoService.save(event.getTodo());
        updateList();
        closeEditor();
    }

    private void deleteTodo(TodoForm.DeleteEvent event) {
        todoService.delete(event.getTodo());
        updateList();
        closeEditor();
    }

}
