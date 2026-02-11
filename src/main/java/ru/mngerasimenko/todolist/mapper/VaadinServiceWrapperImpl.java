package ru.mngerasimenko.todolist.mapper;

import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.VaadinService;
import org.springframework.stereotype.Component;

@Component
public class VaadinServiceWrapperImpl implements VaadinServiceWrapper {

    @Override
    public VaadinResponse getCurrentResponse() {
        return VaadinService.getCurrentResponse();
    }
}
