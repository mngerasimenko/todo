package ru.mngerasimenko.todolist.utils;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;

/**
 * Утилитарный класс.
 */
public class Utils {
    /**
     * Возвращает MAC-адрес первого сетевого интерфейса.
     */
    public static String getMacAddress() {
        StringBuilder sb = new StringBuilder();
        try {
            InetAddress addr = InetAddress.getLocalHost();
            NetworkInterface iface
                    = NetworkInterface.getByInetAddress(addr);
            byte[] mac = iface.getHardwareAddress();

            for (int i = 0; i < mac.length; i++) {
                sb.append(String.format(
                        "%02X%s", mac[i],
                        (i < mac.length - 1) ? "-" : ""));
            }
        } catch (SocketException | UnknownHostException e) {
            e.printStackTrace();
        }
        return sb.toString();
    }
}
