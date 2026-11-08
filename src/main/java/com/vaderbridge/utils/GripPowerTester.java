package com.vaderbridge.utils;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Structure;

/**
 * Изолированный автономный стенд тестирования максимальной мощности рукояток (Grip Motors).
 * Работает напрямую с Win32 XInput API без сетевых помех и циклов сброса.
 */
public class GripPowerTester {

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
            throw new RuntimeException("Библиотеки XInput не найдены в Windows.");
        }

        int XInputGetState(int dwUserIndex, XINPUT_STATE pState);
        int XInputSetState(int dwUserIndex, XINPUT_VIBRATION pVibration);
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("============================================================");
        System.out.println(" Flydigi Vader 4 Pro -> Чистый тест мощности рукояток");
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
            System.err.println("[!] Геймпад не найден на слотах XInput (0..3). Включите контроллер.");
            return;
        }

        System.out.printf("[+] Геймпад обнаружен на XInput слоте #%d\n", userIndex);
        XINPUT_VIBRATION vib = new XINPUT_VIBRATION();

        // -------------------------------------------------------------
        // ТЕСТ 1: Чистый 100% линейный режим (без единого сброса)
        // -------------------------------------------------------------
        System.out.println("\n[*] 1. Чистый линейный 100% разгон рукояток (3 секунды)...");
        vib.wLeftMotorSpeed = (short) 65535;
        vib.wRightMotorSpeed = (short) 65535;
        XInputLib.INSTANCE.XInputSetState(userIndex, vib);
        Thread.sleep(3000);

        stop(userIndex, vib);
        Thread.sleep(800);

        // -------------------------------------------------------------
        // ТЕСТ 2: Раздельная проверка моторов
        // -------------------------------------------------------------
        System.out.println("[*] 2.1. Только ЛЕВАЯ рукоятка (тяжелый эксцентрик, 2 секунды)...");
        vib.wLeftMotorSpeed = (short) 65535;
        vib.wRightMotorSpeed = 0;
        XInputLib.INSTANCE.XInputSetState(userIndex, vib);
        Thread.sleep(2000);

        stop(userIndex, vib);
        Thread.sleep(600);

        System.out.println("[*] 2.2. Только ПРАВАЯ рукоятка (легкий эксцентрик, 2 секунды)...");
        vib.wLeftMotorSpeed = 0;
        vib.wRightMotorSpeed = (short) 65535;
        XInputLib.INSTANCE.XInputSetState(userIndex, vib);
        Thread.sleep(2000);

        stop(userIndex, vib);
        Thread.sleep(800);

        // -------------------------------------------------------------
        // ТЕСТ 3: Импульсный резонанс Overdrive (ударная инерция)
        // -------------------------------------------------------------
        System.out.println("[*] 3. Импульсный режим Overdrive Kick (серия из 10 резких ударов)...");
        for (int i = 0; i < 10; i++) {
            vib.wLeftMotorSpeed = (short) 65535;
            vib.wRightMotorSpeed = (short) 65535;
            XInputLib.INSTANCE.XInputSetState(userIndex, vib);
            Thread.sleep(60); // 60 мс мощный импульс

            vib.wLeftMotorSpeed = 0;
            vib.wRightMotorSpeed = 0;
            XInputLib.INSTANCE.XInputSetState(userIndex, vib);
            Thread.sleep(60); // 60 мс пауза для раскачки груза
        }

        stop(userIndex, vib);
        System.out.println("\n[+] Тестирование завершено. Моторы остановлены.");
    }

    private static void stop(int userIndex, XINPUT_VIBRATION vib) {
        vib.wLeftMotorSpeed = 0;
        vib.wRightMotorSpeed = 0;
        XInputLib.INSTANCE.XInputSetState(userIndex, vib);
    }
}