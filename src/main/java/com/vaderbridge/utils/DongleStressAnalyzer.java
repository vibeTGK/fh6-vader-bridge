package com.vaderbridge.utils;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Structure;

import java.util.concurrent.locks.LockSupport;

/**
 * Аппаратный анализатор пропускной способности и переполнения буфера донгла 2.4G.
 * Измеряет время блокировки системного вызова драйвера Windows (Hardware Lock Latency).
 */
public class DongleStressAnalyzer {

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
        System.out.println(" Flydigi Vader 4 Pro -> Анализатор переполнения донгла 2.4G");
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
            System.err.println("[!] Геймпад не найден. Включите контроллер.");
            return;
        }

        System.out.printf("[+] Геймпад на слоте #%d\n", userIndex);
        System.out.println("[*] Начинаем поочередный тест шины на 60 Гц, 100 Гц и 125 Гц...\n");

        XINPUT_VIBRATION vib = new XINPUT_VIBRATION();
        vib.wLeftMotorSpeed = (short) 65535;
        vib.wRightMotorSpeed = (short) 65535;

        // Тест 1: 60 Гц (16.6 мс)
        runRateTest(userIndex, vib, 60, 16_600_000L, 3000);
        stop(userIndex, vib);
        Thread.sleep(1000);

        // Тест 2: 100 Гц (10.0 мс)
        runRateTest(userIndex, vib, 100, 10_000_000L, 3000);
        stop(userIndex, vib);
        Thread.sleep(1000);

        // Тест 3: 125 Гц (8.0 мс)
        runRateTest(userIndex, vib, 125, 8_000_000L, 3000);
        stop(userIndex, vib);

        try {
            WinMMLib.INSTANCE.timeEndPeriod(1);
        } catch (Throwable ignored) {}

        System.out.println("\n[+] Диагностика радио-шины завершена.");
    }

    private static void runRateTest(int userIndex, XINPUT_VIBRATION vib, int targetHz, long intervalNanos, long durationMs) {
        System.out.printf(">>> ТЕСТ ЧАСТОТЫ %d Гц (Интервал: %.1f мс). Вибрация 3 секунды...\n",
                targetHz, intervalNanos / 1_000_000.0);

        long endTime = System.currentTimeMillis() + durationMs;
        long totalPackets = 0;
        double sumDriverCallUs = 0;
        double maxDriverCallUs = 0;
        int bufferCloggedCount = 0; // Задержки вызова драйвера > 2000 мкс (2 мс)

        while (System.currentTimeMillis() < endTime) {
            long cycleStart = System.nanoTime();

            // Точный замер времени блокировки драйвером Windows
            long callStart = System.nanoTime();
            XInputLib.INSTANCE.XInputSetState(userIndex, vib);
            long callDurationUs = (System.nanoTime() - callStart) / 1000;

            sumDriverCallUs += callDurationUs;
            if (callDurationUs > maxDriverCallUs) maxDriverCallUs = callDurationUs;

            // Если драйвер застрял дольше чем на 2 мс — донгл захлебнулся!
            if (callDurationUs > 2000) {
                bufferCloggedCount++;
                System.out.printf("  [!] ДОНГЛ ЗАХЛЕБНУЛСЯ: блокировка драйвера на %.2f мс!\n", callDurationUs / 1000.0);
            }

            totalPackets++;

            while (System.nanoTime() - cycleStart < intervalNanos) {
                LockSupport.parkNanos(50_000);
            }
        }

        double avgCallUs = sumDriverCallUs / totalPackets;
        System.out.printf("    Итог: Отправлено %d пакетов | Среднее время ответа USB: %.1f мкс | Пик: %.1f мкс\n",
                totalPackets, avgCallUs, maxDriverCallUs);

        if (bufferCloggedCount == 0 && maxDriverCallUs < 3000) {
            System.out.printf("    ✅ ДОНГЛ СВОБОДЕН: буфер справляется со скоростью %d Гц без захлёбываний.\n\n", targetHz);
        } else {
            System.out.printf("    ❌ ДОНГЛ ЗАХЛЁБЫВАЕТСЯ: зафиксировано %d аппаратных зависаний буфера!\n\n", bufferCloggedCount);
        }
    }

    private static void stop(int userIndex, XINPUT_VIBRATION vib) {
        vib.wLeftMotorSpeed = 0;
        vib.wRightMotorSpeed = 0;
        XInputLib.INSTANCE.XInputSetState(userIndex, vib);
    }
}