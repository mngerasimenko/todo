package ru.mngerasimenko.todolist.view;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.component.login.LoginI18n;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;


@Route("login")
@PageTitle("Вход — Список задач")
@AnonymousAllowed
public class LoginView extends VerticalLayout implements BeforeEnterObserver {
    private final LoginForm loginForm = new LoginForm();

    public LoginView() {
        addClassName("login-view");
        setSizeFull();
        setAlignItems(FlexComponent.Alignment.CENTER);
        setJustifyContentMode(FlexComponent.JustifyContentMode.CENTER);

        // Иконка приложения
        Icon appIcon = VaadinIcon.CHECK_SQUARE_O.create();
        appIcon.setSize("48px");
        appIcon.setColor("var(--lumo-primary-color)");
        appIcon.getStyle().set("margin-bottom", "var(--lumo-space-s)");

        // Заголовок
        H1 title = new H1("Список задач");
        title.getStyle()
                .set("margin", "0")
                .set("text-align", "center");

        // Подзаголовок
        Paragraph subtitle = new Paragraph("Управляйте задачами просто и удобно");
        subtitle.getStyle()
                .set("color", "var(--lumo-secondary-text-color)")
                .set("margin-top", "var(--lumo-space-xs)")
                .set("margin-bottom", "var(--lumo-space-l)")
                .set("text-align", "center");

        // Контейнер брендинга
        Div brandingContainer = new Div(appIcon, title, subtitle);
        brandingContainer.getStyle()
                .set("display", "flex")
                .set("flex-direction", "column")
                .set("align-items", "center");

        // Русификация формы входа
        LoginI18n i18n = LoginI18n.createDefault();
        LoginI18n.Form i18nForm = i18n.getForm();
        i18nForm.setTitle("Вход");
        i18nForm.setUsername("Имя пользователя");
        i18nForm.setPassword("Пароль");
        i18nForm.setSubmit("Войти");
        i18nForm.setForgotPassword("");
        i18n.setForm(i18nForm);

        LoginI18n.ErrorMessage errorMessage = i18n.getErrorMessage();
        errorMessage.setTitle("Ошибка входа");
        errorMessage.setMessage("Неверное имя пользователя или пароль. Попробуйте ещё раз.");
        i18n.setErrorMessage(errorMessage);

        loginForm.setI18n(i18n);
        loginForm.setAction("login");
        loginForm.setForgotPasswordButtonVisible(false);

        add(brandingContainer, loginForm);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent beforeEnterEvent) {
        if (beforeEnterEvent.getLocation()
                .getQueryParameters()
                .getParameters()
                .containsKey("error")) {
            loginForm.setError(true);
        }
    }

}
