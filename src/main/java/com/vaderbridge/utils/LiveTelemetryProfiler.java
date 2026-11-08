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
 * Боевой профилировщик без зависаний:
 * Фиксирует redline мотора от аномалий игры (10000 RPM)
 * и мгновенно сбрасывает силу при отпускании газа.
 */
public class LiveTelemetryProfiler {

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

    private static final AtomicInteger targetMotorForce = new AtomicInteger(0);
    private static volatile boolean running = true;

    public static void main(String[] args) {
        System.out.println("============================================================");
        System.out.println(" Vader 4 Pro -> Профайлер (Fix RPM Jitter & No Hang)");
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
            System.err.println("[!] Геймпад не найден на слотах XInput (0..3)!");
            return;
        }

        System.out.printf("[+] Геймпад на слоте #%d. Аппаратный поток 125 Гц запущен...\n", userIndex);

        final int finalUserIndex = userIndex;
        Thread hapticThread = new Thread(() -> {
            XINPUT_VIBRATION vib = new XINPUT_VIBRATION();
            long targetIntervalNanos = 8_000_000L; // Ровно 8 мс (125 Гц)

            while (running) {
                long cycleStart = System.nanoTime();
                int force = targetMotorForce.get();

                vib.wLeftMotorSpeed = (short) force;
                vib.wRightMotorSpeed = (short) force;
                XInputLib.INSTANCE.XInputSetState(finalUserIndex, vib);

                while (System.nanoTime() - cycleStart < targetIntervalNanos) {
                    LockSupport.parkNanos(40_000);
                }
            }

            vib.wLeftMotorSpeed = 0;
            vib.wRightMotorSpeed = 0;
            XInputLib.INSTANCE.XInputSetState(finalUserIndex, vib);
        }, "Haptic-125Hz");

        hapticThread.setDaemon(true);
        hapticThread.setPriority(Thread.MAX_PRIORITY);
        hapticThread.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            try {
                WinMMLib.INSTANCE.timeEndPeriod(1);
            } catch (Throwable ignored) {}
        }));

        runTelemetryListener();
    }

    private static void runTelemetryListener() {
        ForzaPacketParser parser = new ForzaPacketParser();
        byte[] buffer = new byte[1024];
        int port = 5301;

        try (UdpTelemetryReceiver receiver = new UdpTelemetryReceiver(port)) {
            receiver.start();
            System.out.println("[*] Слушатель активен на порту " + port);
            System.out.println("[+] Защита от аномалий 10000 RPM активна. Поехали!\n");

            long lastPacketTimeNanos = System.nanoTime();
            long lastLogTimeMs = System.currentTimeMillis();

            // Калиброванный потолок оборотов для текущей машины
            float lockedMaxRpm = 0.0f;

            while (running) {
                int bytesRead = receiver.receivePacket(buffer);
                long nowNanos = System.nanoTime();

                if (bytesRead <= 0) {
                    continue;
                }

                double udpDeltaMs = (nowNanos - lastPacketTimeNanos) / 1_000_000.0;
                lastPacketTimeNanos = nowNanos;

                ForzaTelemetry telemetry = parser.parse(buffer, bytesRead);
                if (telemetry == null || !telemetry.isRaceOn()) {
                    targetMotorForce.set(0);
                    continue;
                }

                float currentRpm = telemetry.currentEngineRpm();
                float incomingMaxRpm = telemetry.engineMaxRpm();

                // Если машина сменилась или первая калибровка
                if (lockedMaxRpm == 0.0f && incomingMaxRpm > 1000.0f) {
                    lockedMaxRpm = incomingMaxRpm;
                }

                // Игнорируем скачок на 10000, если базовый мотор был ~9050
                if (Math.abs(incomingMaxRpm - lockedMaxRpm) > 500.0f && incomingMaxRpm == 10000.0f) {
                    incomingMaxRpm = lockedMaxRpm;
                } else if (incomingMaxRpm > 1000.0f) {
                    lockedMaxRpm = incomingMaxRpm;
                }

                float realRedline = lockedMaxRpm * 0.905f;

                // Если газ отпущен (accel < 5%) — мгновенно глушим моторы! Никаких зависаний!
                if (telemetry.accelPedal() < 0.05f || currentRpm <= telemetry.engineIdleRpm()) {
                    targetMotorForce.set(0);
                    continue;
                }

                // Расчет чистой силы
                int calculatedForce = 0;
                if (currentRpm >= realRedline) {
                    calculatedForce = 255; // В отсечке строго 255 без колебаний!
                } else {
                    float ratio = (currentRpm - telemetry.engineIdleRpm()) / (realRedline - telemetry.engineIdleRpm());
                    ratio = Math.max(0.0f, Math.min(1.0f, ratio));
                    calculatedForce = (int) (ratio * 255.0f);
                }

                int targetShortSpeed = (calculatedForce >= 254) ? 65535 : (calculatedForce * 65535) / 255;
                targetMotorForce.set(targetShortSpeed);

                long nowMs = System.currentTimeMillis();
                if (nowMs - lastLogTimeMs >= 100) {
                    int bars = (int) (calculatedForce / 12.75);
                    String bar = "=".repeat(bars) + " ".repeat(Math.max(0, 20 - bars));

                    System.out.printf("\r[Forza] Обороты: %5.0f/%5.0f | Газ: %3.0f%% | Δt: %4.1f мс | Мотор: [%s] %3d/255",
                            currentRpm, realRedline, telemetry.accelPedal() * 100.0f, udpDeltaMs, bar, calculatedForce);
                    System.out.flush();
                    lastLogTimeMs = nowMs;
                }
            }
        } catch (IOException e) {
            System.err.println("[!] Ошибка сокета: " + e.getMessage());
        }
    }
}