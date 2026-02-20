package ru.mngerasimenko.todolist.view;

import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.dto.list.ListResponse;
import ru.mngerasimenko.todolist.security.VaadinSecurityService;
import ru.mngerasimenko.todolist.service.TaskListService;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;


public class MainView extends AppLayout implements BeforeEnterObserver {
    private final VaadinSecurityService vaadinSecurityService;
    private final TaskListService taskListService;

    private Select<ListResponse> listSelector;
    private Long selectedListId;
    private final List<Consumer<Long>> listChangeListeners = new ArrayList<>();
    private UserDto authenticatedUser;

    public MainView(VaadinSecurityService vaadinSecurityService,
                    TaskListService taskListService) {
        this.vaadinSecurityService = vaadinSecurityService;
        this.taskListService = taskListService;
        createHeader();
    }

    private void createHeader() {
        authenticatedUser = vaadinSecurityService.getAuthenticatedUser();
        if (authenticatedUser == null) {
            return;
        }

        // Иконка приложения
        Icon appIcon = VaadinIcon.CHECK_SQUARE_O.create();
        appIcon.setSize("28px");
        appIcon.setColor("var(--lumo-primary-color)");

        // Логотип
        H1 logo = new H1("Список задач");
        logo.getStyle()
                .set("font-size", "var(--lumo-font-size-xl)")
                .set("font-weight", "700")
                .set("margin", "0")
                .set("color", "var(--lumo-primary-text-color)");

        // Контейнер логотипа с иконкой
        HorizontalLayout logoLayout = new HorizontalLayout(appIcon, logo);
        logoLayout.setAlignItems(FlexComponent.Alignment.CENTER);
        logoLayout.setSpacing(true);
        logoLayout.getStyle().set("gap", "var(--lumo-space-s)");

        // Селектор списка
        listSelector = new Select<>();
        listSelector.addClassName("list-selector");
        listSelector.setPlaceholder("Выберите список");
        listSelector.setItemLabelGenerator(ListResponse::getName);
        listSelector.getStyle()
                .set("min-width", "180px")
                .set("max-width", "250px");
        refreshListSelector();

        listSelector.addValueChangeListener(e -> {
            ListResponse selected = e.getValue();
            if (selected != null) {
                selectedListId = selected.getId();
                listChangeListeners.forEach(listener -> listener.accept(selectedListId));
            }
        });

        // Кнопка управления списками
        Button manageButton = new Button(new Icon(VaadinIcon.COG));
        manageButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        manageButton.getElement().setAttribute("title", "Управление списками");
        manageButton.addClickListener(e -> openListManagementDialog());

        // Центральная секция (селектор + управление)
        HorizontalLayout centerSection = new HorizontalLayout(listSelector, manageButton);
        centerSection.setAlignItems(FlexComponent.Alignment.CENTER);
        centerSection.setSpacing(true);
        centerSection.getStyle().set("gap", "var(--lumo-space-xs)");

        // Имя пользователя
        Span userNameSpan = new Span(authenticatedUser.getName());
        userNameSpan.getStyle()
                .set("color", "var(--lumo-secondary-text-color)")
                .set("font-weight", "500")
                .set("font-size", "var(--lumo-font-size-s)");

        // Кнопка выхода с иконкой
        Button logout = new Button("Выйти", new Icon(VaadinIcon.SIGN_OUT));
        logout.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        logout.addClassName("header-logout-btn");
        logout.addClickListener(e -> vaadinSecurityService.logout());

        // Правая секция (имя + кнопка выхода)
        HorizontalLayout rightSection = new HorizontalLayout(userNameSpan, logout);
        rightSection.setAlignItems(FlexComponent.Alignment.CENTER);
        rightSection.setSpacing(true);
        rightSection.getStyle().set("gap", "var(--lumo-space-m)");

        // Хедер
        HorizontalLayout header = new HorizontalLayout(logoLayout, centerSection, rightSection);
        header.addClassName("main-header");
        header.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.CENTER);
        header.expand(centerSection);
        header.setWidthFull();
        header.getStyle()
                .set("padding", "0 var(--lumo-space-l)")
                .set("min-height", "56px");

        addToNavbar(header);
    }

    /**
     * Обновляет список в селекторе и выбирает первый элемент.
     */
    public void refreshListSelector() {
        if (authenticatedUser == null) {
            return;
        }
        List<ListResponse> lists = taskListService.getListsByUserId(authenticatedUser.getId());
        listSelector.setItems(lists);

        if (!lists.isEmpty()) {
            // Если текущий список ещё существует — оставить его выбранным
            ListResponse toSelect = lists.stream()
                    .filter(l -> l.getId().equals(selectedListId))
                    .findFirst()
                    .orElse(lists.get(0));
            listSelector.setValue(toSelect);
            selectedListId = toSelect.getId();
        } else {
            selectedListId = null;
        }
    }

    private void openListManagementDialog() {
        ListManagementDialog dialog = new ListManagementDialog(
                taskListService, authenticatedUser.getId(), selectedListId);
        dialog.setOnCloseCallback(changed -> {
            if (changed) {
                refreshListSelector();
                // Уведомить ListView о возможном изменении
                listChangeListeners.forEach(listener -> listener.accept(selectedListId));
            }
        });
        dialog.open();
    }

    /**
     * Возвращает ID текущего выбранного списка.
     */
    public Long getSelectedListId() {
        return selectedListId;
    }

    /**
     * Регистрирует слушателя смены списка.
     * @param listener принимает ID нового выбранного списка
     */
    public void addListChangeListener(Consumer<Long> listener) {
        listChangeListeners.add(listener);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (vaadinSecurityService.getAuthenticatedUser() == null) {
            event.rerouteTo(LoginView.class);
        }
    }

}
