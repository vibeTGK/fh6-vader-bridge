package com.vaderbridge.utils;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Structure;

import java.util.concurrent.locks.LockSupport;

/**
 * Эталонный бенчмарк Space Station:
 * Снимает характеристики нажатия курка и выявляет идеальный профиль вибрации
 * без помех со стороны сети и игры.
 */
public class SpaceStationBenchmark {

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
    }

    public interface WinMMLib extends Library {
        WinMMLib INSTANCE = Native.load("winmm", WinMMLib.class);
        int timeBeginPeriod(int uPeriod);
        int timeEndPeriod(int uPeriod);
    }

    public static void main(String[] args) {
        System.out.println("============================================================");
        System.out.println(" Space Station Benchmark -> Снятие эталонного профиля RT");
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

        System.out.printf("[+] Геймпад подключен на слоте #%d\n", userIndex);
        System.out.println("[*] Открой Space Station -> Вкладку теста вибрации по курку.");
        System.out.println("[*] Плавно нажимай курок RT (Газ) от 0% до 100%...\n");

        long lastLogMs = System.currentTimeMillis();
        int lastTriggerRaw = -1;
        int maxSeenTrigger = 0;

        while (true) {
            long cycleStart = System.nanoTime();

            if (XInputLib.INSTANCE.XInputGetState(userIndex, state) == 0) {
                int rawRt = state.Gamepad.bRightTrigger & 0xFF;
                if (rawRt > maxSeenTrigger) maxSeenTrigger = rawRt;

                long now = System.currentTimeMillis();
                if (now - lastLogMs >= 50) {
                    float percent = (rawRt / 255.0f) * 100.0f;
                    int bars = (int) (rawRt / 12.75);
                    String progressBar = "=".repeat(bars) + " ".repeat(Math.max(0, 20 - bars));

                    System.out.printf("\r[RT КУРОК]: %3d/255 (%5.1f%%) | Шкала: [%s] | Макс: %3d/255",
                            rawRt, percent, progressBar, maxSeenTrigger);
                    System.out.flush();
                    lastLogMs = now;
                }
            }

            // Частота опроса 200 Гц (шаг 5 мс)
            while (System.nanoTime() - cycleStart < 5_000_000L) {
                LockSupport.parkNanos(50_000);
            }
        }
    }
}