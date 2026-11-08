package com.vaderbridge.utils;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Structure;

import java.util.concurrent.locks.LockSupport;

/**
 * Высокоточный калибратор частоты шины XInput.
 * Находит точную частоту пакетов, при которой сторожевой таймер (Watchdog)
 * геймпада перестаёт глушить моторы затыками.
 */
public class SignalComparator {

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
            throw new RuntimeException("XInput не найден в системе Windows.");
        }

        int XInputGetState(int dwUserIndex, XINPUT_STATE pState);
        int XInputSetState(int dwUserIndex, XINPUT_VIBRATION pVibration);
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("============================================================");
        System.out.println(" Vader 4 Pro -> Высокоточный тест частоты шины питания");
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
            System.err.println("[!] Геймпад не найден. Включите контроллер.");
            return;
        }

        System.out.printf("[+] Геймпад обнаружен на XInput слоте #%d\n\n", userIndex);
        XINPUT_VIBRATION vib = new XINPUT_VIBRATION();
        vib.wLeftMotorSpeed = (short) 65535;
        vib.wRightMotorSpeed = (short) 65535;

        // -------------------------------------------------------------
        // ТЕСТ 1: Высокоточный стрим 8 мс (125 Гц - стандарт Space Station)
        // -------------------------------------------------------------
        System.out.println(">>> 1. Тест частоты 125 Гц (каждые 8 миллисекунд)...");
        System.out.println("[*] Вибрация 3 секунды. Оцени плотность и наличие затыков...");
        runHighPrecisionStream(userIndex, vib, 8, 3000);
        stop(userIndex, vib);
        Thread.sleep(1500);

        // -------------------------------------------------------------
        // ТЕСТ 2: Частота 100 Гц (каждые 10 миллисекунд)
        // -------------------------------------------------------------
        System.out.println(">>> 2. Тест частоты 100 Гц (каждые 10 миллисекунд)...");
        System.out.println("[*] Вибрация 3 секунды...");
        runHighPrecisionStream(userIndex, vib, 10, 3000);
        stop(userIndex, vib);
        Thread.sleep(1500);

        // -------------------------------------------------------------
        // ТЕСТ 3: Наш старый режим 20 мс (где были затыки)
        // -------------------------------------------------------------
        System.out.println(">>> 3. Старый режим 50 Гц (каждые 20 миллисекунд)...");
        System.out.println("[*] Вибрация 3 секунды (для сравнения)...");
        runHighPrecisionStream(userIndex, vib, 20, 3000);
        stop(userIndex, vib);

        System.out.println("\n[+] Тест завершен!");
    }

    private static void runHighPrecisionStream(int userIndex, XINPUT_VIBRATION vib, int intervalMs, long totalDurationMs) {
        long endTime = System.currentTimeMillis() + totalDurationMs;
        long intervalNanos = intervalMs * 1_000_000L;

        while (System.currentTimeMillis() < endTime) {
            long cycleStart = System.nanoTime();

            XInputLib.INSTANCE.XInputSetState(userIndex, vib);

            // Наносекундное прецизионное ожидание вместо неточного Thread.sleep
            while (System.nanoTime() - cycleStart < intervalNanos) {
                LockSupport.parkNanos(100_000); // 0.1 мс квант
            }
        }
    }

    private static void stop(int userIndex, XINPUT_VIBRATION vib) {
        vib.wLeftMotorSpeed = 0;
        vib.wRightMotorSpeed = 0;
        XInputLib.INSTANCE.XInputSetState(userIndex, vib);
    }
}