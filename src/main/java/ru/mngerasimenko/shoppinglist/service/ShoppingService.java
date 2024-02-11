package ru.mngerasimenko.shoppinglist.service;

import java.util.List;
import org.springframework.stereotype.Service;
import ru.mngerasimenko.shoppinglist.entity.Shopping;
import ru.mngerasimenko.shoppinglist.repository.ShoppingRepository;
import ru.mngerasimenko.shoppinglist.repository.UserRepository;

@Service
public class ShoppingService {
    private final ShoppingRepository shoppingRepository;
    private final UserRepository userRepository;

    public ShoppingService(ShoppingRepository shoppingRepository, UserRepository userRepository) {
        this.shoppingRepository = shoppingRepository;
        this.userRepository = userRepository;
    }

    public Shopping save(Shopping shopping, long userId) {
        if (!shopping.isNew() && get(shopping.getId(), userId) == null) {
            return null;
        }
        shopping.setUser(userRepository.getReferenceById(userId));
        return shoppingRepository.save(shopping);
    }

    public Shopping get(long id, long userId) {
        Shopping shopping = shoppingRepository.findById(id);
        if (shopping.getUser().getId() == userId) {
            return shopping;
        }
        return null;
    }

    public List<Shopping> getAll(long userId) {
        return shoppingRepository.findAllByUserIdOrderByDateTime(userId);
    }

    public void delete(long userId, long shoppingId) {
        shoppingRepository.deleteByUserIdAndId(userId, shoppingId);
    }
}
