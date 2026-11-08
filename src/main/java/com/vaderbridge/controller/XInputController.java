package com.vaderbridge.controller;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.vaderbridge.model.TriggerFeedback;

import java.io.Closeable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Высокопроизводительный асинхронный контроллер XInput.
 * Задействует мультимедийный таймер ядра Windows (winmm.dll) и прецизионный аппаратный поток 500 Гц (2 мс),
 * перекрывающий любые нулевые команды игры и исключающий затыки моторов.
 */
public class XInputController implements Closeable {

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

    public interface WinMMLib extends Library {
        WinMMLib INSTANCE = Native.load("winmm", WinMMLib.class);
        int timeBeginPeriod(int uPeriod);
        int timeEndPeriod(int uPeriod);
    }

    private int activeUserIndex = -1;
    private final XINPUT_VIBRATION vibration = new XINPUT_VIBRATION();

    // Независимые атомарные регистры целевой мощности (0..65535)
    private final AtomicInteger targetLeftSpeed = new AtomicInteger(0);
    private final AtomicInteger targetRightSpeed = new AtomicInteger(0);

    private volatile boolean running = false;
    private Thread hapticThread;

    // Шаг цикла: ровно 2 мс (500 Гц) — перекрывает 60-120 Гц нули игры без перегрузки USB
    private static final long STEP_NANOS = 2_000_000L;

    public boolean open() {
        try {
            WinMMLib.INSTANCE.timeBeginPeriod(1);
        } catch (Throwable ignored) {}

        XINPUT_STATE state = new XINPUT_STATE();
        for (int i = 0; i < 4; i++) {
            if (XInputLib.INSTANCE.XInputGetState(i, state) == 0) {
                activeUserIndex = i;
                startHapticLoop();
                return true;
            }
        }
        return false;
    }

    private void startHapticLoop() {
        running = true;
        hapticThread = new Thread(() -> {
            XINPUT_VIBRATION workerVib = new XINPUT_VIBRATION();

            while (running) {
                long cycleStart = System.nanoTime();

                int leftVal = targetLeftSpeed.get();
                int rightVal = targetRightSpeed.get();

                // Корректная конвертация в unsigned short 0..65535
                short clampedL = (leftVal >= 65530) ? (short) 0xFFFF : (short) leftVal;
                short clampedR = (rightVal >= 65530) ? (short) 0xFFFF : (short) rightVal;

                workerVib.wLeftMotorSpeed = clampedL;
                workerVib.wRightMotorSpeed = clampedR;

                XInputLib.INSTANCE.XInputSetState(activeUserIndex, workerVib);

                // Наносекундное ожидание для стабильных 500 Гц
                while (System.nanoTime() - cycleStart < STEP_NANOS) {
                    LockSupport.parkNanos(10_000); // 10 мкс квант
                }
            }

            // Гарантированное отключение моторов при выходе
            workerVib.wLeftMotorSpeed = 0;
            workerVib.wRightMotorSpeed = 0;
            XInputLib.INSTANCE.XInputSetState(activeUserIndex, workerVib);
        }, "Hardware-500Hz-Haptic-Pacer");

        hapticThread.setDaemon(true);
        hapticThread.setPriority(Thread.MAX_PRIORITY);
        hapticThread.start();
    }

    public void applyFeedback(TriggerFeedback feedback) {
        if (activeUserIndex == -1 || feedback == null) return;

        int ltClamped = Math.max(0, Math.min(255, feedback.left()));
        int rtClamped = Math.max(0, Math.min(255, feedback.right()));

        // Масштабирование 0..255 -> 0..65535 с честным выходом на 100% (65535)
        int finalL = (ltClamped >= 254) ? 65535 : (ltClamped * 65535) / 255;
        int finalR = (rtClamped >= 254) ? 65535 : (rtClamped * 65535) / 255;

        targetLeftSpeed.set(finalL);
        targetRightSpeed.set(finalR);
    }

    public void stopMotors() {
        targetLeftSpeed.set(0);
        targetRightSpeed.set(0);
    }

    @Override
    public void close() {
        running = false;
        if (hapticThread != null) {
            hapticThread.interrupt();
        }
        stopMotors();
        try {
            WinMMLib.INSTANCE.timeEndPeriod(1);
        } catch (Throwable ignored) {}
    }

    public int getActiveUserIndex() {
        return activeUserIndex;
    }
}