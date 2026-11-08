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
 * Автономный тестовый стенд с агрессивным аппаратным удержанием мощности (Anti-Dropout).
 * Исключает затыки, сбросы в ноль и перебивание сигнала со стороны движка игры.
 */
public class ForzaHapticTestBench {

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

    // Целевая мощность, выставляемая телеметрией игры (0..65535)
    private static final AtomicInteger sharedTargetPower = new AtomicInteger(0);
    private static volatile boolean running = true;

    public static void main(String[] args) {
        System.out.println("============================================================");
        System.out.println(" ТЕСТ: Агрессивный аппаратный Pacer (Anti-Dropout Testbench)");
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
            System.err.println("[!] Геймпад не найден. Подключите геймпад.");
            return;
        }

        System.out.printf("[+] Геймпад на слоте #%d\n", userIndex);
        System.out.println("[*] В игре включи 1-ю передачу и зажми газ в пол.");

        final int finalUserIndex = userIndex;

        // ---------------------------------------------------------------------
        // АППАРАТНЫЙ ПОТОК (2 мс = 500 Гц):
        // Оптимальная частота 500 Гц: мгновенно перекрывает нули игры,
        // не перегружая стек очереди XInput Windows.
        // ---------------------------------------------------------------------
        Thread hardwareMotorThread = new Thread(() -> {
            XINPUT_VIBRATION vib = new XINPUT_VIBRATION();
            final long stepNanos = 2_000_000L; // 2 миллисекунды (500 Гц)

            while (running) {
                long start = System.nanoTime();

                int power = sharedTargetPower.get();
                // 100% максимальная мощность (65535 / 0xFFFF)
                short clamped = (power > 0) ? (short) 0xFFFF : 0;

                vib.wLeftMotorSpeed = clamped;
                vib.wRightMotorSpeed = clamped;
                XInputLib.INSTANCE.XInputSetState(finalUserIndex, vib);

                while (System.nanoTime() - start < stepNanos) {
                    LockSupport.parkNanos(10_000);
                }
            }

            vib.wLeftMotorSpeed = 0;
            vib.wRightMotorSpeed = 0;
            XInputLib.INSTANCE.XInputSetState(finalUserIndex, vib);
        }, "Hardware-500Hz-UltraPacer");

        hardwareMotorThread.setDaemon(true);
        hardwareMotorThread.setPriority(Thread.MAX_PRIORITY);
        hardwareMotorThread.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            sharedTargetPower.set(0);
            try {
                WinMMLib.INSTANCE.timeEndPeriod(1);
            } catch (Throwable ignored) {}
        }));

        runTelemetryLoop();
    }

    private static void runTelemetryLoop() {
        ForzaPacketParser parser = new ForzaPacketParser();
        byte[] buffer = new byte[1024];

        long lastPacketTime = System.currentTimeMillis();
        long lastLogTime = System.currentTimeMillis();
        long lastThrottlePressTime = 0;

        try (UdpTelemetryReceiver receiver = new UdpTelemetryReceiver(5301)) {
            receiver.start();
            System.out.println("[*] UDP Слушатель активен на 127.0.0.1:5301. Ожидание пакетов...\n");

            while (running) {
                int bytesRead = receiver.receivePacket(buffer);
                long now = System.currentTimeMillis();

                if (bytesRead <= 0) {
                    if (now - lastPacketTime > 500) {
                        sharedTargetPower.set(0);
                    }
                    continue;
                }
                lastPacketTime = now;

                ForzaTelemetry t = parser.parse(buffer, bytesRead);
                if (t == null) {
                    continue;
                }

                float throttle = t.accelPedal();
                float rpm = t.currentEngineRpm();

                // Фиксируем удержание газа: курок нажат больше 10%
                if (throttle > 0.10f || rpm > 3000.0f) {
                    lastThrottlePressTime = now;
                }

                // Запас удержания 500 мс: полностью сглаживает провалы зажигания Forza
                boolean isGasHeld = (now - lastThrottlePressTime) < 500;

                // Полная мощность: 65535 (100% мощности моторов)
                int currentPower = isGasHeld ? 65535 : 0;
                sharedTargetPower.set(currentPower);

                if (now - lastLogTime >= 100) {
                    System.out.printf("\r[Forza] Газ: %3.0f%% | RPM: %5.0f | Поток 500Гц: %5d (%s)",
                            throttle * 100.0f,
                            rpm,
                            currentPower,
                            isGasHeld ? "100% МОНОЛИТ" : "ВЫКЛ");
                    System.out.flush();
                    lastLogTime = now;
                }
            }
        } catch (IOException e) {
            System.err.println("[!] Ошибка сокета: " + e.getMessage());
        }
    }
}