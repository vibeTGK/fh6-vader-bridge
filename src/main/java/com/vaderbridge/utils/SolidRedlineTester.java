package com.vaderbridge.utils;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.vaderbridge.model.ForzaTelemetry;
import com.vaderbridge.telemetry.ForzaPacketParser;
import com.vaderbridge.telemetry.UdpTelemetryReceiver;

import java.io.IOException;

/**
 * Чистый аппаратный тест монолитной вибрации в Forza.
 * Полностью исключает стробоскопы, сбросы, модуляции и эффекты пробуксовки.
 * Выдаёт 100% сплошной ток при оборотах выше 88%.
 */
public class SolidRedlineTester {

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
        System.out.println(" Forza -> Чистый тест монолитной отсечки (Zero Strobe)");
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
            System.err.println("[!] Геймпад не найден!");
            return;
        }

        System.out.printf("[+] Геймпад на слоте #%d по кабелю Type-C.\n", userIndex);
        System.out.println("[*] В игре включи 1-ю передачу и зажми газ в отсечку.\n");

        ForzaPacketParser parser = new ForzaPacketParser();
        byte[] buffer = new byte[1024];
        XINPUT_VIBRATION vib = new XINPUT_VIBRATION();
        short currentSent = 0;

        try (UdpTelemetryReceiver receiver = new UdpTelemetryReceiver(5301)) {
            receiver.start();

            while (true) {
                int len = receiver.receivePacket(buffer);
                if (len <= 0) continue;

                ForzaTelemetry t = parser.parse(buffer, len);
                if (t == null || !t.isRaceOn()) {
                    if (currentSent != 0) {
                        vib.wLeftMotorSpeed = 0;
                        vib.wRightMotorSpeed = 0;
                        XInputLib.INSTANCE.XInputSetState(userIndex, vib);
                        currentSent = 0;
                    }
                    continue;
                }

                float maxRpm = t.engineMaxRpm();
                float currentRpm = t.currentEngineRpm();
                float ratio = (maxRpm > 0) ? (currentRpm / maxRpm) : 0;

                short target;
                // Никаких стробоскопов и прерываний! Чистая сплошная стена:
                if (ratio >= 0.88f) {
                    target = (short) 65535; // 100% монолит
                } else if (ratio >= 0.60f) {
                    float p = (ratio - 0.60f) / 0.28f;
                    target = (short) (p * 45000); // Плавное нарастание без рывков
                } else {
                    target = 0;
                }

                // Шлём только при реальном изменении (без спама)
                if (Math.abs(target - currentSent) > 1000) {
                    vib.wLeftMotorSpeed = target;
                    vib.wRightMotorSpeed = target;
                    XInputLib.INSTANCE.XInputSetState(userIndex, vib);
                    currentSent = target;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
