package ru.mngerasimenko.todolist.service;

import org.springframework.stereotype.Service;
import ru.mngerasimenko.todolist.model.Mac2User;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.MacRepository;

@Service
public class MacService {
    private final MacRepository macRepository;

    public MacService(MacRepository macRepository) {
        this.macRepository = macRepository;
    }

    public Mac2User getMacByUser(long userId){
        return macRepository.findByUserId(userId);
    }

    public User getUserByMacAddress(String macAddress) {
        Mac2User mac2User = macRepository.findByMacAddress(macAddress);
        if (mac2User != null){
            return mac2User.getUser();
        }
        return null;
    }

    public void save(Mac2User mac2User) {
        macRepository.save(mac2User);
    }
}
