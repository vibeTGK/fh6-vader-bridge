package com.vaderbridge.utils;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.vaderbridge.model.ForzaTelemetry;
import com.vaderbridge.telemetry.ForzaPacketParser;
import com.vaderbridge.telemetry.UdpTelemetryReceiver;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Промышленный умный мост с аппаратной фильтрацией сетевых дропов Forza (800 RPM Glitch)
 * и защитой радиоканала донгла Flydigi от переполнения буфера.
 */
public class DongleSmartBridge {

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

    public interface WinMMLib extends Library {
        WinMMLib INSTANCE = Native.load("winmm", WinMMLib.class);
        int timeBeginPeriod(int uPeriod);
        int timeEndPeriod(int uPeriod);
    }

    private static final AtomicInteger targetSpeed = new AtomicInteger(0);
    private static volatile boolean running = true;

    public static void main(String[] args) {
        System.out.println("============================================================");
        System.out.println(" Flydigi Vader 4 Pro -> Мост с защитой от дропов 800 RPM");
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

        System.out.printf("[+] Геймпад на слоте #%d. Аппаратный контроллер запущен...\n", userIndex);

        final int finalUserIndex = userIndex;

        // Поток отправки с защитой от захлебывания донгла
        Thread radioThread = new Thread(() -> {
            XINPUT_VIBRATION vib = new XINPUT_VIBRATION();
            int lastSentVal = -1;
            long lastSentTimeMs = 0;

            while (running) {
                long cycleStart = System.nanoTime();
                int currentTarget = targetSpeed.get();
                long nowMs = System.currentTimeMillis();

                // Шлем в донгл ТОЛЬКО если есть изменение или каждые 45 мс (Heartbeat)
                boolean valueChanged = Math.abs(currentTarget - lastSentVal) > 600;
                boolean heartbeatDue = (nowMs - lastSentTimeMs >= 45);

                if (valueChanged || heartbeatDue) {
                    vib.wLeftMotorSpeed = (short) currentTarget;
                    vib.wRightMotorSpeed = (short) currentTarget;

                    XInputLib.INSTANCE.XInputSetState(finalUserIndex, vib);

                    lastSentVal = currentTarget;
                    lastSentTimeMs = nowMs;
                }

                // Шаг проверки: 12 мс (безопасно для радиоэфира 2.4G)
                while (System.nanoTime() - cycleStart < 12_000_000L) {
                    LockSupport.parkNanos(100_000);
                }
            }

            vib.wLeftMotorSpeed = 0;
            vib.wRightMotorSpeed = 0;
            XInputLib.INSTANCE.XInputSetState(finalUserIndex, vib);
        }, "Radio-Worker");

        radioThread.setDaemon(true);
        radioThread.setPriority(Thread.MAX_PRIORITY);
        radioThread.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            try {
                WinMMLib.INSTANCE.timeEndPeriod(1);
            } catch (Throwable ignored) {}
        }));

        runReceiverLoop();
    }

    private static void runReceiverLoop() {
        ForzaPacketParser parser = new ForzaPacketParser();
        byte[] buffer = new byte[1024];
        int port = 5301;

        try (UdpTelemetryReceiver receiver = new UdpTelemetryReceiver(port)) {
            receiver.start();
            System.out.println("[*] Слушатель активен на порту " + port);
            System.out.println("[+] Анти-Глитч фильтр включен: сбои на 800 RPM отсекаются на лету.\n");

            long lastLogMs = System.currentTimeMillis();
            float lastValidRpm = 0.0f;
            float smoothedRpm = 0.0f;
            int droppedGlitchesCount = 0;

            while (running) {
                int bytesRead = receiver.receivePacket(buffer);
                if (bytesRead <= 0) continue;

                ForzaTelemetry telemetry = parser.parse(buffer, bytesRead);
                if (telemetry == null || !telemetry.isRaceOn()) {
                    targetSpeed.set(0);
                    continue;
                }

                float currentRpm = telemetry.currentEngineRpm();
                float maxRpm = telemetry.engineMaxRpm();
                float idleRpm = telemetry.engineIdleRpm();
                float speed = telemetry.speedKmh();
                float realRedline = maxRpm * 0.905f;

                // =========================================================================
                // АНТИ-ГЛИТЧ ФИЛЬТР: Отсекаем фантомные провалы игры на 800 RPM
                // =========================================================================
                if (speed > 10.0f && lastValidRpm > 2500.0f && currentRpm <= 1200.0f) {
                    // Это тот самый мусорный пакет игры! Игнорируем его!
                    droppedGlitchesCount++;
                    continue;
                }
                lastValidRpm = currentRpm;

                if (currentRpm <= idleRpm && speed < 3.0f) {
                    targetSpeed.set(0);
                    smoothedRpm = idleRpm;
                    continue;
                }

                // Сглаживание микроколебаний тахометра
                smoothedRpm += (currentRpm - smoothedRpm) * 0.35f;

                float progress = (smoothedRpm - idleRpm) / (realRedline - idleRpm);
                progress = Math.max(0.0f, Math.min(1.0f, progress));

                int finalSpeed = (progress >= 0.96f) ? 65535 : (int) (progress * 65535.0f);
                targetSpeed.set(finalSpeed);

                long now = System.currentTimeMillis();
                if (now - lastLogMs >= 100) {
                    int force255 = (finalSpeed * 255) / 65535;
                    int bars = (int) (force255 / 12.75);
                    String bar = "=".repeat(bars) + " ".repeat(Math.max(0, 20 - bars));

                    System.out.printf("\r[Forza] RPM: %5.0f/%5.0f | Скорость: %3.0f км/ч | Отсечено сбоев: %2d | Мотор: [%s] %3d/255",
                            currentRpm, realRedline, speed, droppedGlitchesCount, bar, force255);
                    System.out.flush();
                    lastLogMs = now;
                }
            }
        } catch (IOException e) {
            System.err.println("[!] Ошибка сокета: " + e.getMessage());
        }
    }
}