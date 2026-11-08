package com.vaderbridge.utils;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Structure;

import java.util.concurrent.locks.LockSupport;

/**
 * 5-секундный прецизионный бенчмарк задержки отклика и стабильности шины геймпада.
 * Собирает статистику за сессию и выводит полный математический отчет о наличии затыков.
 */
public class TriggerInputToVibeLatencyTimer {

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
        System.out.println(" Flydigi Vader 4 Pro -> 5-Секундный Бенчмарк Задержки и Затыков");
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
            System.err.println("[!] Геймпад не обнаружен! Включите контроллер.");
            return;
        }

        System.out.printf("[+] Геймпад на слоте #%d.\n", userIndex);
        System.out.println("[*] Приготовьтесь! Старт через 3 секунды. Положите палец на курок RT...");

        for (int i = 3; i > 0; i--) {
            System.out.printf("    Старт через %d...\n", i);
            Thread.sleep(1000);
        }

        System.out.println("\n🔥 ТЕСТ ПОШЕЛ! ЖМИТЕ И КАЧАЙТЕ КУРОК RT (5 СЕКУНД)!");

        XINPUT_VIBRATION vib = new XINPUT_VIBRATION();
        long targetIntervalNanos = 5_000_000L; // 5 мс (200 Гц)
        long sessionDurationMs = 5000;
        long sessionEndMs = System.currentTimeMillis() + sessionDurationMs;

        // Метрики сессии
        long totalCycles = 0;
        double sumTotalLatencyMs = 0;
        double minLatencyMs = 999.0;
        double maxLatencyMs = 0;

        double sumReadTimeUs = 0;
        double sumWriteTimeUs = 0;

        int jitterCountOver8ms = 0;
        int jitterCountOver12ms = 0;
        int jitterCountOver15ms = 0; // Критический порог Watchdog

        long previousCycleStart = System.nanoTime();
        long lastCountdownLog = System.currentTimeMillis();

        while (System.currentTimeMillis() < sessionEndMs) {
            long cycleStart = System.nanoTime();

            // Интервал с предыдущего цикла
            if (totalCycles > 0) {
                double deltaMs = (cycleStart - previousCycleStart) / 1_000_000.0;
                if (deltaMs >= 15.0) jitterCountOver15ms++;
                else if (deltaMs >= 12.0) jitterCountOver12ms++;
                else if (deltaMs >= 8.0) jitterCountOver8ms++;
            }
            previousCycleStart = cycleStart;

            // 1. Замер чтения
            long tReadStart = System.nanoTime();
            XInputLib.INSTANCE.XInputGetState(userIndex, state);
            long readUs = (System.nanoTime() - tReadStart) / 1000;
            sumReadTimeUs += readUs;

            int rawRt = state.Gamepad.bRightTrigger & 0xFF;
            int force = (rawRt * 65535) / 255;
            vib.wLeftMotorSpeed = (short) force;
            vib.wRightMotorSpeed = (short) force;

            // 2. Замер записи
            long tWriteStart = System.nanoTime();
            XInputLib.INSTANCE.XInputSetState(userIndex, vib);
            long writeUs = (System.nanoTime() - tWriteStart) / 1000;
            sumWriteTimeUs += writeUs;

            // Полная задержка пайплайна
            double totalLatMs = (System.nanoTime() - cycleStart) / 1_000_000.0;
            sumTotalLatencyMs += totalLatMs;
            if (totalLatMs > maxLatencyMs) maxLatencyMs = totalLatMs;
            if (totalLatMs < minLatencyMs) minLatencyMs = totalLatMs;

            totalCycles++;

            long now = System.currentTimeMillis();
            if (now - lastCountdownLog >= 500) {
                long remainingSec = Math.max(0, (sessionEndMs - now) / 1000 + 1);
                int bars = (int) (rawRt / 12.75);
                String bar = "=".repeat(bars) + " ".repeat(Math.max(0, 20 - bars));
                System.out.printf("\r[Осталось: %d сек] RT: %3d/255 [%s] | Текущая задержка: %5.3f мс",
                        remainingSec, rawRt, bar, totalLatMs);
                System.out.flush();
                lastCountdownLog = now;
            }

            // Удержание 5 мс
            while (System.nanoTime() - cycleStart < targetIntervalNanos) {
                LockSupport.parkNanos(30_000);
            }
        }

        // Остановка моторов
        vib.wLeftMotorSpeed = 0;
        vib.wRightMotorSpeed = 0;
        XInputLib.INSTANCE.XInputSetState(userIndex, vib);

        try {
            WinMMLib.INSTANCE.timeEndPeriod(1);
        } catch (Throwable ignored) {}

        // -------------------------------------------------------------
        // ИТОГОВЫЙ ОТЧЕТ СЕССИИ (Ground Truth Benchmark)
        // -------------------------------------------------------------
        System.out.println("\n\n============================================================");
        System.out.println("             ИТОГОВЫЙ ОТЧЕТ ЗАМЕРА (5 СЕКУНД)");
        System.out.println("============================================================");
        System.out.printf(" Всего обработано циклов:        %d\n", totalCycles);
        System.out.printf(" Средняя задержка пайплайна:      %.3f мс\n", (sumTotalLatencyMs / totalCycles));
        System.out.printf(" Минимальная задержка:            %.3f мс\n", minLatencyMs);
        System.out.printf(" Максимальный пик задержки:       %.3f мс\n", maxLatencyMs);
        System.out.printf(" Среднее время чтения курка:     %.1f мкс\n", (sumReadTimeUs / totalCycles));
        System.out.printf(" Среднее время записи в мотор:   %.1f мкс\n", (sumWriteTimeUs / totalCycles));
        System.out.println("------------------------------------------------------------");
        System.out.printf(" Микро-паузы (> 8 мс):            %d раз\n", jitterCountOver8ms);
        System.out.printf(" Заметные задержки (> 12 мс):     %d раз\n", jitterCountOver12ms);
        System.out.printf(" КРИТИЧЕСКИЕ ЗАТЫКИ (> 15 мс):    %d раз\n", jitterCountOver15ms);
        System.out.println("============================================================");

        if (jitterCountOver15ms == 0 && maxLatencyMs < 5.0) {
            System.out.println("✅ ВЫВОД: Аппаратный канал RT -> Моторы работает без затыков!");
            System.out.println("   Задержка чистая, шина полностью стабильна.");
        } else {
            System.out.printf("❌ ВЫВОД: Обнаружено %d критических провалов времени!\n", jitterCountOver15ms);
            System.out.println("   Именно в эти моменты палец ощущает срыв вибрации.");
        }
    }
}