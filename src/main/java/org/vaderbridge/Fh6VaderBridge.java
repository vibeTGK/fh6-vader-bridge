package org.vaderbridge;

import org.hid4java.HidDevice;
import org.hid4java.HidManager;
import org.hid4java.HidServices;
import org.hid4java.HidServicesSpecification;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

/**
 * FH6 / Forza Motorsport Telemetry Bridge для контроллера Flydigi Vader 4 Pro на Java.
 * Перехватывает UDP DataOut пакеты и транслирует вибрацию курков через HID-протокол Flydigi.
 */
public class Fh6VaderBridge {

    // --- Известные идентификаторы Flydigi (Vendor IDs / Product IDs) ---
    private static final List<Integer> FLYDIGI_VIDS = Arrays.asList(
            0x2E24, // Стандартный Flydigi VID
            0x04B4, // Cypress DInput VID
            0x37D7  // Новые ревизии донглов
    );

    private static final List<Integer> KNOWN_PIDS = Arrays.asList(
            0x2412, // Vader 4 Pro / Vader 3 DInput
            0x2411, // Vader 2 / 3 Pro
            0x0603, // 2.4G Dongle
            0x0604, // 2.4G Dongle
            0x2501, // Dongle Apex/Vader
            0x2502
    );

    // Команда вибромоторов курков Flydigi (Vendor Command 0x18)
    private static final byte CMD_TRIGGER_RUMBLE = 0x18;
    private static final byte SUB_CMD_MOTORS = 0x04;

    // Пороги срабатывания по умолчанию
    private static final double ABS_SLIP_THRESHOLD = 0.85;      // Порог срыва передней оси (тормоз/ABS)
    private static final double TRACTION_SLIP_THRESHOLD = 0.90; // Порог пробуксовки ведущих колес (газ)
    private static final int UDP_PORT = 5300;

    /**
     * Контейнер данных телеметрии Forza Horizon / Motorsport
     */
    public record ForzaTelemetry(
            boolean isRaceOn,
            float engineMaxRpm,
            float engineIdleRpm,
            float currentEngineRpm,
            float speedKmh,
            float accelPedal,
            float brakePedal,
            float slipRatioFL,
            float slipRatioFR,
            float slipRatioRL,
            float slipRatioRR,
            float rumbleFL,
            float rumbleFR
    ) {}

    public static void main(String[] args) {
        boolean testMode = args.length > 0 && args[0].equalsIgnoreCase("--test");

        System.out.println("============================================================");
        System.out.println(" Flydigi Vader 4 Pro -> FH6 Java Telemetry Bridge");
        System.out.println("============================================================");

        // Инициализация подсистемы HID
        HidServicesSpecification hidServicesSpecification = new HidServicesSpecification();
        hidServicesSpecification.setAutoStart(false);
        HidServices hidServices = HidManager.getHidServices(hidServicesSpecification);
        hidServices.start();

        HidDevice device = findVader4Pro(hidServices);
        if (device == null) {
            System.err.println("[!] Контроллер Vader 4 Pro не обнаружен.");
            System.err.println("    Подключите геймпад через USB-кабель или 2.4G донгл.");
            hidServices.shutdown();
            return;
        }

        System.out.println("[+] Контроллер подключен: " + device.getProduct() + " (" + device.getPath() + ")");

        if (!device.isOpen() && !device.open()) {
            System.err.println("[!] Не удалось открыть HID-дескриптор геймпада.");
            System.err.println("    Закройте Flydigi Space Station, если он запущен.");
            hidServices.shutdown();
            return;
        }

        if (testMode) {
            runHapticTest(device);
            device.close();
            hidServices.shutdown();
            return;
        }

        // Запуск моста телеметрии
        runBridgeLoop(device);

        device.close();
        hidServices.shutdown();
        System.out.println("[*] Работа программы завершена.");
    }

    /**
     * Поиск контроллера Flydigi среди системных HID устройств
     */
    private static HidDevice findVader4Pro(HidServices hidServices) {
        for (HidDevice hidDevice : hidServices.getAttachedHidDevices()) {
            int vid = hidDevice.getVendorId();
            int pid = hidDevice.getProductId();
            int usagePage = hidDevice.getUsagePage();

            if (FLYDIGI_VIDS.contains(vid) || KNOWN_PIDS.contains(pid)) {
                // Выбираем управляющий Vendor Usage Page (0xFFA0 или 0xFF00)
                if (usagePage == 0xFFA0 || usagePage == 0xFF00 || usagePage == 0x0001 || usagePage == 0) {
                    return hidDevice;
                }
            }
        }
        return null;
    }

    /**
     * Основной цикл: чтение UDP пакетов от Forza и отправка вибро в геймпад
     */
    private static void runBridgeLoop(HidDevice device) {
        System.out.println("[*] Прослушивание UDP сокета на порту: " + UDP_PORT);
        System.out.println("[*] В игре включите Data Out: 127.0.0.1 : " + UDP_PORT);
        System.out.println("[+] Мост активен. Нажмите Ctrl+C для выхода.\n");

        byte[] buffer = new byte[1024];

        try (DatagramSocket socket = new DatagramSocket(UDP_PORT)) {
            socket.setSoTimeout(1000); // 1 сек таймаут

            long lastLogTime = System.currentTimeMillis();

            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    // Если данных от игры нет — глушим курки
                    sendTriggerVibration(device, 0, 0);
                    continue;
                }

                ForzaTelemetry telemetry = parseForzaPacket(packet.getData(), packet.getLength());
                if (telemetry == null || !telemetry.isRaceOn()) {
                    sendTriggerVibration(device, 0, 0);
                    continue;
                }

                // 1. Левый курок: Торможение и срабатывание ABS
                int ltForce = 0;
                if (telemetry.brakePedal() > 0.05f) {
                    float frontSlip = Math.max(Math.abs(telemetry.slipRatioFL()), Math.abs(telemetry.slipRatioFR()));
                    if (frontSlip > ABS_SLIP_THRESHOLD) {
                        double intensity = Math.min(1.0, (frontSlip - ABS_SLIP_THRESHOLD) * 2.2);
                        // Пульсация ABS (~25 Гц)
                        boolean pulse = ((System.currentTimeMillis() / 40) % 2) == 0;
                        ltForce = (int) (intensity * 255.0 * (pulse ? 1.0 : 0.6));
                    }
                }

                // 2. Правый курок: Пробуксовка ведущих колес при газе
                int rtForce = 0;
                if (telemetry.accelPedal() > 0.05f) {
                    float rearSlip = Math.max(Math.abs(telemetry.slipRatioRL()), Math.abs(telemetry.slipRatioRR()));
                    float driveSlip = Math.max(rearSlip, Math.max(Math.abs(telemetry.slipRatioFL()), Math.abs(telemetry.slipRatioFR())));
                    if (driveSlip > TRACTION_SLIP_THRESHOLD) {
                        double intensity = Math.min(1.0, (driveSlip - TRACTION_SLIP_THRESHOLD) * 2.5);
                        rtForce = (int) (intensity * 240.0);
                    }
                }

                // 3. Бордюры и неровности (Curb Rumble)
                if (telemetry.rumbleFL() > 0.15f) {
                    ltForce = Math.max(ltForce, (int) (telemetry.rumbleFL() * 190.0));
                }
                if (telemetry.rumbleFR() > 0.15f) {
                    rtForce = Math.max(rtForce, (int) (telemetry.rumbleFR() * 190.0));
                }

                // Отправка в курки
                sendTriggerVibration(device, ltForce, rtForce);

                // Вывод состояния в консоль раз в секунду
                long now = System.currentTimeMillis();
                if (now - lastLogTime >= 1000) {
                    System.out.printf("\r[Forza] Скорость: %5.1f км/ч | Обороты: %5.0f | LT (ABS): %3d | RT (Газ): %3d",
                            telemetry.speedKmh(), telemetry.currentEngineRpm(), ltForce, rtForce);
                    System.out.flush();
                    lastLogTime = now;
                }
            }
        } catch (IOException e) {
            System.err.println("[!] Сетевая ошибка сокета: " + e.getMessage());
        } finally {
            sendTriggerVibration(device, 0, 0);
        }
    }

    /**
     * Парсер бинарного пакета Forza Sled/Dash
     */
    private static ForzaTelemetry parseForzaPacket(byte[] rawData, int length) {
        if (length < 232) {
            return null;
        }

        ByteBuffer bb = ByteBuffer.wrap(rawData, 0, length);
        bb.order(ByteOrder.LITTLE_ENDIAN);

        boolean isRaceOn = bb.getInt(0) != 0;
        float maxRpm = bb.getFloat(8);
        float idleRpm = bb.getFloat(12);
        float currentRpm = bb.getFloat(16);

        // Slip ratios (байты 68 - 83)
        float slipFL = bb.getFloat(68);
        float slipFR = bb.getFloat(72);
        float slipRL = bb.getFloat(76);
        float slipRR = bb.getFloat(80);

        // Rumble surfaces (байты 116 - 131)
        float rumbleFL = bb.getFloat(116);
        float rumbleFR = bb.getFloat(120);

        float speedKmh = 0.0f;
        float accelPedal = 0.0f;
        float brakePedal = 0.0f;

        if (length >= 324) { // Dash формат
            float speedMps = bb.getFloat(256);
            speedKmh = speedMps * 3.6f;
            int accelRaw = bb.get(303) & 0xFF;
            int brakeRaw = bb.get(304) & 0xFF;
            accelPedal = accelRaw / 255.0f;
            brakePedal = brakeRaw / 255.0f;
        } else { // Sled формат
            float vx = bb.getFloat(32);
            float vy = bb.getFloat(36);
            float vz = bb.getFloat(40);
            speedKmh = (float) (Math.sqrt(vx * vx + vy * vy + vz * vz) * 3.6);
            accelPedal = (currentRpm > idleRpm + 1000) ? 1.0f : 0.0f;
            brakePedal = (slipFL < -0.4f || slipFR < -0.4f) ? 1.0f : 0.0f;
        }

        return new ForzaTelemetry(
                isRaceOn, maxRpm, idleRpm, currentRpm, speedKmh,
                accelPedal, brakePedal, slipFL, slipFR, slipRL, slipRR,
                rumbleFL, rumbleFR
        );
    }

    /**
     * Отправка 32-байтного Vendor Output Report в Vader 4 Pro
     */
    public static synchronized void sendTriggerVibration(HidDevice device, int lt, int rt) {
        if (device == null || !device.isOpen()) return;

        byte clampedLt = (byte) Math.max(0, Math.min(255, lt));
        byte clampedRt = (byte) Math.max(0, Math.min(255, rt));

        byte[] report = new byte[32];
        report[0] = 0x00;                // Report ID
        report[1] = CMD_TRIGGER_RUMBLE; // 0x18
        report[2] = SUB_CMD_MOTORS;     // 0x04
        report[3] = clampedLt;          // Левый курок (Тормоз/ABS)
        report[4] = clampedRt;          // Правый курок (Газ/Traction)
        report[5] = 0x00;                // Левый нижний эксцентрик
        report[6] = 0x00;                // Правый нижний эксцентрик

        device.write(report, 32, (byte) 0x00);
    }

    /**
     * Аппаратный тест моторов курков
     */
    private static void runHapticTest(HidDevice device) {
        System.out.println("[*] Тестирование левого курка (LT - ABS)...");
        try {
            for (int i = 0; i <= 255; i += 25) {
                sendTriggerVibration(device, i, 0);
                Thread.sleep(40);
            }
            sendTriggerVibration(device, 0, 0);
            Thread.sleep(250);

            System.out.println("[*] Тестирование правого курка (RT - Газ)...");
            for (int i = 0; i <= 255; i += 25) {
                sendTriggerVibration(device, 0, i);
                Thread.sleep(40);
            }
            sendTriggerVibration(device, 0, 0);
            Thread.sleep(250);

            System.out.println("[*] Проверка обоих курков одновременно...");
            sendTriggerVibration(device, 220, 220);
            Thread.sleep(600);
            sendTriggerVibration(device, 0, 0);

            System.out.println("[+] Тест успешно завершен!");
        } catch (InterruptedException ignored) {}
    }
}