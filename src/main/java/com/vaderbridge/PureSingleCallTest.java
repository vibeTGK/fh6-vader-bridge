package com.vaderbridge;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Structure;

public class PureSingleCallTest {

    @Structure.FieldOrder({"wLeftMotorSpeed", "wRightMotorSpeed"})
    public static class XINPUT_VIBRATION extends Structure {
        public short wLeftMotorSpeed;
        public short wRightMotorSpeed;
    }

    public interface XInputLib extends Library {
        XInputLib INSTANCE = Native.load("xinput1_4", XInputLib.class);
        int XInputSetState(int dwUserIndex, XINPUT_VIBRATION pVibration);
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("[*] Тест одного вызова без спама и циклов...");

        XINPUT_VIBRATION vib = new XINPUT_VIBRATION();
        // Включаем среднюю ровную мощность (~32000)
        vib.wLeftMotorSpeed = (short) 64000;
        vib.wRightMotorSpeed = (short) 64000;

        System.out.println("[+] Отправляем команду РОВНО ОДИН РАЗ");
        XInputLib.INSTANCE.XInputSetState(0, vib);

        System.out.println("[*] Моторы должны гудеть 4 секунды МАКСИМАЛЬНО РОДНО И ПЛОТНО...");
        Thread.sleep(4000);

        System.out.println("[-] Выключаем");
        vib.wLeftMotorSpeed = 0;
        vib.wRightMotorSpeed = 0;
        XInputLib.INSTANCE.XInputSetState(0, vib);
        System.out.println("[+] Тест завершен");
    }
}