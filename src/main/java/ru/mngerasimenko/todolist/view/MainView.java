package ru.mngerasimenko.todolist.view;

import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.theme.lumo.LumoUtility;
import ru.mngerasimenko.todolist.security.SecurityService;


public class MainView extends AppLayout {
    private final SecurityService securityService;

    public MainView(SecurityService securityService) {
        this.securityService = securityService;
        createHeader();
        //createDrawer();
    }

    private void createHeader() {
        H1 logo = new H1("Todo list");
        logo.addClassNames(
                LumoUtility.FontSize.LARGE,
                LumoUtility.Margin.MEDIUM);

        String userName = securityService.getAuthenticatedUser().getName();
        Button logout = new Button("Log out " + userName, e -> securityService.logout());

        var header = new HorizontalLayout(new DrawerToggle(), logo, logout);

        header.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.CENTER);
        header.expand(logo);
        header.setWidthFull();
        header.addClassNames(
                LumoUtility.Padding.Vertical.NONE,
                LumoUtility.Padding.Horizontal.MEDIUM);

        addToNavbar(header);
    }

//    private void createDrawer() {
//        addToDrawer(new VerticalLayout(
//                new RouterLink("List", ListView.class)
//        ));
//    }

}