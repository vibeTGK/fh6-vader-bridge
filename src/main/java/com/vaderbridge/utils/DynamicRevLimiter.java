package com.vaderbridge.utils;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.vaderbridge.model.ForzaTelemetry;
import com.vaderbridge.telemetry.ForzaPacketParser;
import com.vaderbridge.telemetry.UdpTelemetryReceiver;

import java.io.IOException;

public class DynamicRevLimiter {

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

    public static void main(String[] args) {
        System.out.println("============================================================");
        System.out.println(" Тест: RPM > 2000 -> 100% мощности с полным логом XInput");
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
            System.err.println("[!] Геймпад не найден.");
            return;
        }

        System.out.printf("[+] Геймпад на слоте #%d\n", userIndex);

        ForzaPacketParser parser = new ForzaPacketParser();
        byte[] buffer = new byte[1024];
        XINPUT_VIBRATION vib = new XINPUT_VIBRATION();

        boolean isVibrating = false;
        long lastLogMs = System.currentTimeMillis();

        try (UdpTelemetryReceiver receiver = new UdpTelemetryReceiver(5301)) {
            receiver.start();

            while (true) {
                int len = receiver.receivePacket(buffer);
                if (len <= 0) continue;

                ForzaTelemetry t = parser.parse(buffer, len);
                if (t == null || !t.isRaceOn()) {
                    if (isVibrating) {
                        vib.wLeftMotorSpeed = 0;
                        vib.wRightMotorSpeed = 0;
                        XInputLib.INSTANCE.XInputSetState(userIndex, vib);
                        isVibrating = false;
                    }
                    continue;
                }

                float rpm = t.currentEngineRpm();
                boolean shouldVibrate = rpm > 2000.0f;

                short power = shouldVibrate ? (short) 65535 : 0;
                vib.wLeftMotorSpeed = power;
                vib.wRightMotorSpeed = power;

                // Непрерывная отправка на каждом кадре
                int setResult = XInputLib.INSTANCE.XInputSetState(userIndex, vib);

                // Детальный лог при смене состояния
                if (shouldVibrate != isVibrating) {
                    int getResult = XInputLib.INSTANCE.XInputGetState(userIndex, state);

                    int unsignedL = Short.toUnsignedInt(vib.wLeftMotorSpeed);
                    int unsignedR = Short.toUnsignedInt(vib.wRightMotorSpeed);

                    System.out.printf("\n[XINPUT ВЫЗОВ] Слот: #%d | Цель: %s\n",
                            userIndex, shouldVibrate ? "ВКЛЮЧИТЬ (100%)" : "ВЫКЛЮЧИТЬ (0%)");
                    System.out.printf("  -> Отправлено в структуру: Left=%d (0x%04X, raw short: %d), Right=%d (0x%04X, raw short: %d)\n",
                            unsignedL, unsignedL, vib.wLeftMotorSpeed,
                            unsignedR, unsignedR, vib.wRightMotorSpeed);
                    System.out.printf("  <- Ответ XInputSetState: %d %s\n",
                            setResult,
                            setResult == 0 ? "[0 = ERROR_SUCCESS (Команда принята ядром Windows)]"
                                    : (setResult == 1167 ? "[1167 = ERROR_DEVICE_NOT_CONNECTED]"
                                    : "[ОШИБКА Win32: " + setResult + "]"));
                    System.out.printf("  <- Проверка XInputGetState: %s (Packet #%d, LT=%d, RT=%d)\n\n",
                            getResult == 0 ? "СВЯЗЬ ЕСТЬ (OK)" : "НЕТ СВЯЗИ (Код: " + getResult + ")",
                            state.dwPacketNumber,
                            state.Gamepad.bLeftTrigger & 0xFF,
                            state.Gamepad.bRightTrigger & 0xFF);

                    isVibrating = shouldVibrate;
                }

                long now = System.currentTimeMillis();
                if (now - lastLogMs >= 150) {
                    System.out.printf("\r[RPM: %5.0f] -> Моторы: %s",
                            rpm, isVibrating ? "[100% ВКЛ - 65535]" : "[  0% ВЫКЛ]");
                    System.out.flush();
                    lastLogMs = now;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}