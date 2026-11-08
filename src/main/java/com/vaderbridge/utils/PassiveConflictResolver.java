package com.vaderbridge.utils;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Structure;

/**
 * Тест устранения конфликта двух процессов (Java vs Space Station).
 * Полностью исключает фоновый спам в XInput.
 * Позволяет проверить: исчезнет ли просадка мощности в Space Station при запущенной Java.
 */
public class PassiveConflictResolver {

    @Structure.FieldOrder({"wButtons", "bLeftTrigger", "bRightTrigger", "sThumbLX", "sThumbLY", "sThumbRX", "sThumbRY"})
    public static class XINPUT_GAMEPAD extends Structure {
        public short wButtons;
        public byte bLeftTrigger;
        public byte bRightTrigger;
        public short sThumbLX;
        public short sThumbLY;
        public short sThumbRX;
        public short sThumbRY;
    }

    @Structure.FieldOrder({"dwPacketNumber", "Gamepad"})
    public static class XINPUT_STATE extends Structure {
        public int dwPacketNumber;
        public XINPUT_GAMEPAD Gamepad;
    }

    @Structure.FieldOrder({"wLeftMotorSpeed", "wRightMotorSpeed"})
    public static class XINPUT_VIBRATION extends Structure {
        public short wLeftMotorSpeed;
        public short wRightMotorSpeed;
    }

    public interface XInputLib extends Library {
        XInputLib INSTANCE = loadXInput();

        static XInputLib loadXInput() {
            String[] dlls = {"xinput1_4", "xinput1_3", "xinput9_1_0"};
            for (String dll : dlls) {
                try {
                    return Native.load(dll, XInputLib.class);
                } catch (UnsatisfiedLinkError ignored) {}
            }
            throw new RuntimeException("XInput не найден.");
        }

        int XInputGetState(int dwUserIndex, XINPUT_STATE pState);
        int XInputSetState(int dwUserIndex, XINPUT_VIBRATION pVibration);
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("============================================================");
        System.out.println(" Vader 4 Pro -> Проверка конфликта шины (Zero-Spam Test)");
        System.out.println("============================================================");

        int userIndex = -1;
        XINPUT_STATE state = new XINPUT_STATE();
        for (int i = 0; i < 4; i++) {
            if (XInputLib.INSTANCE.XInputGetState(i, state) == 0) {
                userIndex = i;
                break;
            }
        }

        if (userIndex == -1) {
            System.err.println("[!] Геймпад не найден!");
            return;
        }

        System.out.printf("[+] Геймпад на слоте #%d\n\n", userIndex);

        // -------------------------------------------------------------
        // ЭТАП 1: Полная тишина Java (проверяем Space Station)
        // -------------------------------------------------------------
        System.out.println(">>> ЭТАП 1: Java работает, но ВООБЩЕ НЕ ТРОГАЕТ XInput (Тишина в шине)");
        System.out.println("[*] Открой Space Station и нажми тест курка/вибрации прямо сейчас!");
        System.out.println("[*] У тебя есть 10 секунд. Оцени: стала ли вибрация МОЩНОЙ И РОВНОЙ, как при выключенной Java?");

        for (int i = 10; i > 0; i--) {
            System.out.printf("\r    Осталось времени на тест: %2d сек...", i);
            System.out.flush();
            Thread.sleep(1000);
        }
        System.out.println("\n[+] Этап 1 завершен.\n");

        // -------------------------------------------------------------
        // ЭТАП 2: Имитация нашего старого кода (Спам в шину 0 каждые 10 мс)
        // -------------------------------------------------------------
        System.out.println(">>> ЭТАП 2: Java начинает спамить нулевой вибрацией (Имитация конфликта процессов)");
        System.out.println("[*] Снова жми тест курка в Space Station!");
        System.out.println("[*] Почувствуй: вернулись ли те самые затыки и просадка мощности?");

        XINPUT_VIBRATION zeroVib = new XINPUT_VIBRATION();
        zeroVib.wLeftMotorSpeed = 0;
        zeroVib.wRightMotorSpeed = 0;

        long endTime = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < endTime) {
            XInputLib.INSTANCE.XInputSetState(userIndex, zeroVib);
            Thread.sleep(15);
        }

        System.out.println("\n\n[+] Тест полностью завершен.");
    }
}