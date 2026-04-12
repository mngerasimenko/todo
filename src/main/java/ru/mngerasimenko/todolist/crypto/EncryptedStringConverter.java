package ru.mngerasimenko.todolist.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * JPA AttributeConverter для прозрачного шифрования/расшифровки строковых полей Entity.
 * Применяется через @Convert(converter = EncryptedStringConverter.class) на полях Entity.
 *
 * Использует статический доступ к CryptoService (через CryptoServiceHolder),
 * потому что JPA может создавать converter без Spring injection (@DataJpaTest, etc).
 * Если CryptoService не инициализирован — данные проходят без изменений.
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        CryptoService crypto = CryptoServiceHolder.getInstance();
        return crypto != null ? crypto.encrypt(attribute) : attribute;
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        CryptoService crypto = CryptoServiceHolder.getInstance();
        return crypto != null ? crypto.decrypt(dbData) : dbData;
    }
}
