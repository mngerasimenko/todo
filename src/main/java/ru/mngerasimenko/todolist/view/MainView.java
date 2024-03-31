package ru.mngerasimenko.todolist.view;

import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;


//@PermitAll
//@Route(value = "main")
//@PageTitle("Todo list")
public class MainView extends VerticalLayout {

    public MainView() {
        add(new Text("Todo list."));
    }

}