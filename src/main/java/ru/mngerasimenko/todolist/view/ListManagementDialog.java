package ru.mngerasimenko.todolist.view;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import ru.mngerasimenko.todolist.dto.list.ListMemberResponse;
import ru.mngerasimenko.todolist.dto.list.ListResponse;
import ru.mngerasimenko.todolist.service.TaskListService;

import java.util.List;
import java.util.function.Consumer;

/**
 * Диалог управления списками задач.
 * Содержит вкладки: мои списки, создание, вступление, участники.
 */
public class ListManagementDialog extends Dialog {

    private final TaskListService taskListService;
    private final Long userId;
    private final Long currentListId;
    private Consumer<Boolean> onCloseCallback;

    // Флаг: были ли изменения (создание/вступление/выход)
    private boolean listsChanged = false;

    // Вкладка "Мои списки"
    private VerticalLayout myListsContent;

    // Вкладка "Участники"
    private VerticalLayout membersContent;

    public ListManagementDialog(TaskListService taskListService, Long userId, Long currentListId) {
        this.taskListService = taskListService;
        this.userId = userId;
        this.currentListId = currentListId;

        addClassName("list-management-dialog");
        setHeaderTitle("Управление списками");
        setWidth("500px");
        setMaxHeight("80vh");
        setCloseOnOutsideClick(true);
        setCloseOnEsc(true);

        TabSheet tabSheet = new TabSheet();
        tabSheet.setWidthFull();

        myListsContent = createMyListsTab();
        tabSheet.add(new Tab("Мои списки"), myListsContent);
        tabSheet.add(new Tab("Создать"), createCreateTab());
        tabSheet.add(new Tab("Вступить"), createJoinTab());

        membersContent = createMembersTab();
        tabSheet.add(new Tab("Участники"), membersContent);

        add(tabSheet);

        // Кнопка закрытия
        Button closeButton = new Button("Закрыть", e -> close());
        closeButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        getFooter().add(closeButton);

        addOpenedChangeListener(e -> {
            if (!e.isOpened() && onCloseCallback != null) {
                onCloseCallback.accept(listsChanged);
            }
        });
    }

    /**
     * Устанавливает callback при закрытии диалога.
     * @param callback принимает true если списки изменились
     */
    public void setOnCloseCallback(Consumer<Boolean> callback) {
        this.onCloseCallback = callback;
    }

    // === Вкладка "Мои списки" ===

    private VerticalLayout createMyListsTab() {
        VerticalLayout layout = new VerticalLayout();
        layout.setPadding(false);
        layout.setSpacing(true);
        refreshMyLists(layout);
        return layout;
    }

    private void refreshMyLists(VerticalLayout layout) {
        layout.removeAll();
        List<ListResponse> lists = taskListService.getListsByUserId(userId);

        if (lists.isEmpty()) {
            Span empty = new Span("У вас нет списков. Создайте новый или вступите в существующий.");
            empty.getStyle().set("color", "var(--lumo-secondary-text-color)");
            layout.add(empty);
            return;
        }

        for (ListResponse list : lists) {
            layout.add(createListCard(list));
        }
    }

    private Div createListCard(ListResponse list) {
        Div card = new Div();
        card.addClassName("list-card");

        // Название и роль
        Span name = new Span(list.getName());
        name.getStyle().set("font-weight", "600");

        Span role = new Span(list.getRole());
        role.getStyle()
                .set("font-size", "var(--lumo-font-size-s)")
                .set("color", "var(--lumo-secondary-text-color)")
                .set("padding", "2px 8px")
                .set("border-radius", "var(--lumo-border-radius-s)")
                .set("background-color", "ADMIN".equals(list.getRole())
                        ? "var(--lumo-primary-color-10pct)"
                        : "var(--lumo-contrast-5pct)");

        HorizontalLayout nameRow = new HorizontalLayout(name, role);
        nameRow.setAlignItems(FlexComponent.Alignment.CENTER);
        nameRow.setSpacing(true);

        // Выделение текущего списка
        if (list.getId().equals(currentListId)) {
            Icon activeIcon = VaadinIcon.CHECK.create();
            activeIcon.setSize("16px");
            activeIcon.setColor("var(--lumo-success-color)");
            nameRow.add(activeIcon);
        }

        // Кнопка "Покинуть"
        Button leaveButton = new Button("Покинуть", new Icon(VaadinIcon.SIGN_OUT));
        leaveButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
        leaveButton.addClickListener(e -> confirmLeave(list));

        HorizontalLayout cardContent = new HorizontalLayout(nameRow, leaveButton);
        cardContent.setWidthFull();
        cardContent.setAlignItems(FlexComponent.Alignment.CENTER);
        cardContent.expand(nameRow);

        card.add(cardContent);
        return card;
    }

    private void confirmLeave(ListResponse list) {
        ConfirmDialog confirm = new ConfirmDialog();
        confirm.setHeader("Покинуть список");
        confirm.setText("Вы уверены, что хотите покинуть список \"" + list.getName()
                + "\"? Ваши приватные задачи в этом списке будут удалены.");
        confirm.setCancelable(true);
        confirm.setCancelText("Отмена");
        confirm.setConfirmText("Покинуть");
        confirm.setConfirmButtonTheme("error primary");
        confirm.addConfirmListener(e -> {
            try {
                taskListService.leaveList(list.getId(), userId);
                listsChanged = true;
                refreshMyLists(myListsContent);
                showSuccess("Вы покинули список \"" + list.getName() + "\"");
            } catch (Exception ex) {
                showError(ex.getMessage());
            }
        });
        confirm.open();
    }

    // === Вкладка "Создать" ===

    private VerticalLayout createCreateTab() {
        VerticalLayout layout = new VerticalLayout();
        layout.setPadding(false);
        layout.setSpacing(true);

        TextField nameField = new TextField("Название списка");
        nameField.setWidthFull();
        nameField.setPlaceholder("Введите название...");
        nameField.setClearButtonVisible(true);

        PasswordField passwordField = new PasswordField("Пароль списка");
        passwordField.setWidthFull();
        passwordField.setPlaceholder("Придумайте пароль...");
        passwordField.setHelperText("Пароль нужен для вступления других участников");

        Button createButton = new Button("Создать список", new Icon(VaadinIcon.PLUS));
        createButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        createButton.addClickListener(e -> {
            String name = nameField.getValue().trim();
            String password = passwordField.getValue();

            if (name.isEmpty()) {
                nameField.setInvalid(true);
                nameField.setErrorMessage("Введите название");
                return;
            }
            if (password.isEmpty()) {
                passwordField.setInvalid(true);
                passwordField.setErrorMessage("Введите пароль");
                return;
            }

            try {
                taskListService.createList(name, password, userId);
                listsChanged = true;
                nameField.clear();
                passwordField.clear();
                refreshMyLists(myListsContent);
                showSuccess("Список \"" + name + "\" создан");
            } catch (Exception ex) {
                showError(ex.getMessage());
            }
        });

        layout.add(nameField, passwordField, createButton);
        return layout;
    }

    // === Вкладка "Вступить" ===

    private VerticalLayout createJoinTab() {
        VerticalLayout layout = new VerticalLayout();
        layout.setPadding(false);
        layout.setSpacing(true);

        TextField nameField = new TextField("Название списка");
        nameField.setWidthFull();
        nameField.setPlaceholder("Введите название списка...");
        nameField.setClearButtonVisible(true);

        PasswordField passwordField = new PasswordField("Пароль списка");
        passwordField.setWidthFull();
        passwordField.setPlaceholder("Введите пароль...");

        Button joinButton = new Button("Вступить в список", new Icon(VaadinIcon.SIGN_IN));
        joinButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        joinButton.addClickListener(e -> {
            String name = nameField.getValue().trim();
            String password = passwordField.getValue();

            if (name.isEmpty()) {
                nameField.setInvalid(true);
                nameField.setErrorMessage("Введите название");
                return;
            }
            if (password.isEmpty()) {
                passwordField.setInvalid(true);
                passwordField.setErrorMessage("Введите пароль");
                return;
            }

            try {
                taskListService.joinList(name, password, userId);
                listsChanged = true;
                nameField.clear();
                passwordField.clear();
                refreshMyLists(myListsContent);
                showSuccess("Вы вступили в список \"" + name + "\"");
            } catch (Exception ex) {
                showError(ex.getMessage());
            }
        });

        layout.add(nameField, passwordField, joinButton);
        return layout;
    }

    // === Вкладка "Участники" ===

    private VerticalLayout createMembersTab() {
        VerticalLayout layout = new VerticalLayout();
        layout.setPadding(false);
        layout.setSpacing(true);
        refreshMembers(layout);
        return layout;
    }

    private void refreshMembers(VerticalLayout layout) {
        layout.removeAll();

        if (currentListId == null) {
            Span empty = new Span("Выберите список, чтобы увидеть участников");
            empty.getStyle().set("color", "var(--lumo-secondary-text-color)");
            layout.add(empty);
            return;
        }

        try {
            List<ListMemberResponse> members = taskListService.getMembers(currentListId, userId);

            if (members.isEmpty()) {
                Span empty = new Span("В этом списке нет участников");
                empty.getStyle().set("color", "var(--lumo-secondary-text-color)");
                layout.add(empty);
                return;
            }

            for (ListMemberResponse member : members) {
                layout.add(createMemberCard(member));
            }
        } catch (Exception ex) {
            showError(ex.getMessage());
        }
    }

    private Div createMemberCard(ListMemberResponse member) {
        Div card = new Div();
        card.addClassName("member-card");

        // Иконка пользователя
        Icon userIcon = VaadinIcon.USER.create();
        userIcon.setSize("20px");
        userIcon.setColor("var(--lumo-primary-color)");

        // Имя
        Span name = new Span(member.getUserName());
        name.getStyle().set("font-weight", "500");

        // Роль
        Span role = new Span(member.getRole());
        role.getStyle()
                .set("font-size", "var(--lumo-font-size-s)")
                .set("color", "var(--lumo-secondary-text-color)")
                .set("padding", "2px 8px")
                .set("border-radius", "var(--lumo-border-radius-s)")
                .set("background-color", "ADMIN".equals(member.getRole())
                        ? "var(--lumo-primary-color-10pct)"
                        : "var(--lumo-contrast-5pct)");

        HorizontalLayout cardContent = new HorizontalLayout(userIcon, name, role);
        cardContent.setAlignItems(FlexComponent.Alignment.CENTER);
        cardContent.setSpacing(true);

        card.add(cardContent);
        return card;
    }

    // === Вспомогательные методы ===

    private void showSuccess(String message) {
        Notification notification = Notification.show(message, 3000,
                Notification.Position.BOTTOM_CENTER);
        notification.addThemeVariants(NotificationVariant.LUMO_SUCCESS);
    }

    private void showError(String message) {
        Notification notification = Notification.show(message, 4000,
                Notification.Position.BOTTOM_CENTER);
        notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
    }
}
