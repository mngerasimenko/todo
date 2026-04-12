package ru.mngerasimenko.todolist.crypto;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Статический holder для CryptoService.
 * Нужен для JPA AttributeConverter, который создаётся Hibernate без Spring injection.
 * При старте Spring-контекста сохраняет экземпляр CryptoService в статическое поле.
 * В тестах (@DataJpaTest) CryptoService может не быть — тогда шифрование отключено.
 */
@Component
@RequiredArgsConstructor
public class CryptoServiceHolder {

    private final CryptoService cryptoService;
    private static CryptoService instance;

    @PostConstruct
    void init() {
        instance = cryptoService;
    }

    public static CryptoService getInstance() {
        return instance;
    }
}
