package ru.mngerasimenko.shoppinglist.controller;

import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.mngerasimenko.shoppinglist.entity.Shopping;
import ru.mngerasimenko.shoppinglist.service.ShoppingService;

@RestController
@RequestMapping("/api/shoppings")
public class ShoppingRestController {
    private final ShoppingService shoppingService;

    public ShoppingRestController(ShoppingService shoppingService) {
        this.shoppingService = shoppingService;
    }

    @GetMapping("/{userId}")
    public List<Shopping> getAll(@PathVariable long userId) {
        return shoppingService.getAll(userId);
    }

    @PostMapping("/{userId}")
    public Shopping add(@RequestBody Shopping shopping, @PathVariable long userId) {
        return shoppingService.save(shopping, userId);
    }

    @PutMapping("/{userId}")
    public Shopping update(@RequestBody Shopping shopping, @PathVariable long userId) {
        return shoppingService.save(shopping, userId);
    }

    @DeleteMapping("/{userId}/{shoppingId}")
    public String delete(@PathVariable long userId, @PathVariable long shoppingId) {
        shoppingService.delete(userId, shoppingId);
        return "User with ID = " + shoppingId + " was deleted";
    }
}
