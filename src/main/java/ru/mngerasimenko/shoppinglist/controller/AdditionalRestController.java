package ru.mngerasimenko.shoppinglist.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.mngerasimenko.shoppinglist.utils.Status;

@RestController
@RequestMapping("/api")
public class AdditionalRestController {
    @GetMapping("/status")
    public Status getStatus() {
        return new Status();
    }

}
