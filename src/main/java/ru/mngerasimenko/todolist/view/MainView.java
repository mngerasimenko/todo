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
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import ru.mngerasimenko.todolist.security.SecurityService;


public class MainView extends AppLayout implements BeforeEnterObserver {
    private final SecurityService securityService;

    public MainView(SecurityService securityService) {
        this.securityService = securityService;
        createHeader();
    }

    private void createHeader() {
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

        // Имя пользователя
        String userName = securityService.getAuthenticatedUser().getName();
        Span userNameSpan = new Span(userName);
        userNameSpan.getStyle()
                .set("color", "var(--lumo-secondary-text-color)")
                .set("font-weight", "500")
                .set("font-size", "var(--lumo-font-size-s)");

        // Кнопка выхода с иконкой
        Button logout = new Button("Выйти", new Icon(VaadinIcon.SIGN_OUT));
        logout.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        logout.addClassName("header-logout-btn");
        logout.addClickListener(e -> securityService.logout());

        // Правая секция (имя + кнопка выхода)
        HorizontalLayout rightSection = new HorizontalLayout(userNameSpan, logout);
        rightSection.setAlignItems(FlexComponent.Alignment.CENTER);
        rightSection.setSpacing(true);
        rightSection.getStyle().set("gap", "var(--lumo-space-m)");

        // Хедер
        HorizontalLayout header = new HorizontalLayout(logoLayout, rightSection);
        header.addClassName("main-header");
        header.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.CENTER);
        header.expand(logoLayout);
        header.setWidthFull();
        header.getStyle()
                .set("padding", "0 var(--lumo-space-l)")
                .set("min-height", "56px");

        addToNavbar(header);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (securityService.getAuthenticatedUser() == null) {
            event.rerouteTo(LoginView.class);
        }
    }

}
