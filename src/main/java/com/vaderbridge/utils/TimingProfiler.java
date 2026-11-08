package com.vaderbridge.utils;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Structure;

import java.util.concurrent.locks.LockSupport;

/**
 * Аппаратный профилировщик таймингов шины XInput.
 * Замеряет реальную дельту времени (Δt) между отправками пакетов
 * и выявляет скрытые фризы планировщика Windows.
 */
public class TimingProfiler {

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

    public interface WinMMLib extends Library {
        WinMMLib INSTANCE = Native.load("winmm", WinMMLib.class);
        int timeBeginPeriod(int uPeriod);
        int timeEndPeriod(int uPeriod);
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("============================================================");
        System.out.println(" Vader 4 Pro -> Профайлер реального времени шины XInput");
        System.out.println("============================================================");

        try {
            WinMMLib.INSTANCE.timeBeginPeriod(1);
        } catch (Throwable ignored) {}

        int userIndex = -1;
        XINPUT_STATE state = new XINPUT_STATE();
        for (int i = 0; i < 4; i++) {
            if (XInputLib.INSTANCE.XInputGetState(i, state) == 0) {
                userIndex = i;
                break;
            }
        }

        if (userIndex == -1) {
            System.err.println("[!] Геймпад не обнаружен!");
            return;
        }

        System.out.printf("[+] Геймпад на слоте #%d. Запускаем тест 7.00 мс на 4 секунды...\n", userIndex);
        System.out.println("[*] Внимание: замеряем РЕАЛЬНЫЕ миллисекунды между пакетами!\n");

        XINPUT_VIBRATION vib = new XINPUT_VIBRATION();
        vib.wLeftMotorSpeed = (short) 65535;
        vib.wRightMotorSpeed = (short) 65535;

        long targetIntervalNanos = 7_000_000L; // Целевые 7 мс
        long testEndTime = System.currentTimeMillis() + 4000;

        long previousCycleStart = System.nanoTime();
        int totalPackets = 0;
        int jitterCountOver10ms = 0;
        int jitterCountOver15ms = 0;
        double maxDeltaMs = 0;
        double minDeltaMs = 999.0;
        double sumDeltaMs = 0;

        while (System.currentTimeMillis() < testEndTime) {
            long cycleStart = System.nanoTime();

            // Замеряем, сколько РЕАЛЬНО прошло времени с прошлого вызова
            if (totalPackets > 0) {
                double deltaMs = (cycleStart - previousCycleStart) / 1_000_000.0;
                sumDeltaMs += deltaMs;
                if (deltaMs > maxDeltaMs) maxDeltaMs = deltaMs;
                if (deltaMs < minDeltaMs) minDeltaMs = deltaMs;

                if (deltaMs >= 15.0) {
                    jitterCountOver15ms++;
                    System.out.printf("[!] КРИТИЧЕСКИЙ СБОЙ: пауза %.2f мс! (Вот он затык!)\n", deltaMs);
                } else if (deltaMs >= 10.0) {
                    jitterCountOver10ms++;
                }
            }
            previousCycleStart = cycleStart;

            // Замеряем время самого обращения к драйверу Windows
            long callStart = System.nanoTime();
            XInputLib.INSTANCE.XInputSetState(userIndex, vib);
            long callDurationNanos = System.nanoTime() - callStart;

            totalPackets++;

            // Выравниваем цикл под целевые 7.00 мс
            while (System.nanoTime() - cycleStart < targetIntervalNanos) {
                LockSupport.parkNanos(20_000);
            }
        }

        // Останавливаем моторы
        vib.wLeftMotorSpeed = 0;
        vib.wRightMotorSpeed = 0;
        XInputLib.INSTANCE.XInputSetState(userIndex, vib);

        try {
            WinMMLib.INSTANCE.timeEndPeriod(1);
        } catch (Throwable ignored) {}

        // -------------------------------------------------------------
        // ИТОГОВЫЙ ОТЧЕТ ИСТИНЫ
        // -------------------------------------------------------------
        System.out.println("\n============================================================");
        System.out.println("              ИТОГОВЫЙ ОТЧЕТ ТЕЛЕМЕТРИИ ТАЙМИНГА");
        System.out.println("============================================================");
        System.out.printf(" Всего отправлено пакетов:    %d\n", totalPackets);
        System.out.printf(" Среднее реальное время шага: %.3f мс (цель была 7.000 мс)\n", (sumDeltaMs / (totalPackets - 1)));
        System.out.printf(" Минимальный интервал:        %.3f мс\n", minDeltaMs);
        System.out.printf(" Максимальный интервал (пик): %.3f мс\n", maxDeltaMs);
        System.out.printf(" Задержек больше 10 мс:       %d раз\n", jitterCountOver10ms);
        System.out.printf(" Задержек больше 15 мс (КРАХ):%d раз\n", jitterCountOver15ms);
        System.out.println("============================================================");

        if (jitterCountOver15ms > 0) {
            System.out.println("❌ ДИАГНОЗ: Windows не держит 7 мс! Были провалы свыше 15 мс.");
            System.out.println("   Именно в эти моменты сторожевой таймер Vader 4 Pro тушит мотор.");
        } else {
            System.out.println("✅ ДИАГНОЗ: Тайминг идеален! Пакеты идут ровно без единого дропа.");
            System.out.println("   Значит, дело НЕ в частоте миллисекунд, а в параметрах самого пакета.");
        }
    }
}