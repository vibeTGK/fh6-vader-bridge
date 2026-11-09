package com.vaderbridge;

import com.vaderbridge.config.BridgeConfig;
import com.vaderbridge.controller.XInputController;
import com.vaderbridge.model.ForzaTelemetry;
import com.vaderbridge.model.TriggerFeedback;
import com.vaderbridge.physics.HapticFeedbackCalculator;
import com.vaderbridge.telemetry.ForzaPacketParser;
import com.vaderbridge.telemetry.UdpTelemetryReceiver;

import java.io.IOException;

public class Main {

    public static void main(String[] args) {
        BridgeConfig config = BridgeConfig.fromArgs(args);

        System.out.println("============================================================");
        System.out.println(" Flydigi Vader 4 Pro -> SimHub True Wheel Lock Calibration");
        System.out.printf(" Режим: ABS/WheelLock [%s, Сила: %d%%]\n",
                config.absEnabled() ? "ВКЛ" : "ВЫКЛ", config.absPowerPercent());
        System.out.println("============================================================");

        XInputController controller = new XInputController();
        if (!controller.open()) {
            System.err.println("[!] Контроллер не найден на слотах XInput (0..3). Включите геймпад.");
            return;
        }

        System.out.printf("[+] Геймпад подключен на XInput слоте #%d (Шина 500 Гц)\n", controller.getActiveUserIndex());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[*] Остановка вибромоторов...");
            controller.close();
        }));

        runPipeline(config, controller);
    }

    private static void runPipeline(BridgeConfig config, XInputController controller) {
        ForzaPacketParser parser = new ForzaPacketParser();
        HapticFeedbackCalculator calculator = new HapticFeedbackCalculator(config);
        byte[] rawBuffer = new byte[1024];

        int port = (config.udpPort() != 5300 && config.udpPort() != 20055) ? config.udpPort() : 5301;

        try (UdpTelemetryReceiver receiver = new UdpTelemetryReceiver(port)) {
            receiver.start();
            System.out.println("[*] UDP порт: 127.0.0.1:" + port);
            System.out.println("[*] Тест: поворот/легкий снос = ТИШИНА. Реальный клин тормозом = отклик.\n");

            long lastPacketTime = System.currentTimeMillis();
            long lastLog = System.currentTimeMillis();

            while (true) {
                int bytesRead = receiver.receivePacket(rawBuffer);
                long now = System.currentTimeMillis();

                if (bytesRead <= 0) {
                    if (now - lastPacketTime > 400) {
                        controller.stopMotors();
                    }
                    continue;
                }
                lastPacketTime = now;

                ForzaTelemetry telemetry = parser.parse(rawBuffer, bytesRead);
                TriggerFeedback feedback = calculator.calculate(telemetry);
                controller.applyFeedback(feedback);

                if (telemetry != null && now - lastLog >= 80) {
                    float carSpeed = telemetry.speedKmh();
                    float wheelLinearFL = Math.max(0.0f, telemetry.wheelSpeedFL() * 0.33f * 3.6f);
                    float wheelLinearFR = Math.max(0.0f, telemetry.wheelSpeedFR() * 0.33f * 3.6f);
                    float brakePct = telemetry.brakePedal() * 100.0f;

                    float lockDepth = 0.0f;
                    if (carSpeed > 10.0f) {
                        float minWheel = Math.min(wheelLinearFL, wheelLinearFR);
                        lockDepth = 1.0f - (minWheel / carSpeed);
                    }

                    String status;
                    if (brakePct < 5.0f) {
                        status = "Тормоз отпущен   ";
                    } else if (lockDepth < 0.40f) {
                        status = "Колеса крутятся  ";
                    } else {
                        status = "КЛИН ТОРМОЗА!    ";
                    }

                    int barsL = (int) (feedback.left() / 12.75);
                    String barL = "=".repeat(barsL) + " ".repeat(Math.max(0, 20 - barsL));

                    System.out.printf("\r[Авто: %5.1f км/ч | Колесо: %5.1f км/ч] | LT: %3.0f%% | Клин: %4.2f | %s | L: [%s] %3d",
                            carSpeed, Math.min(wheelLinearFL, wheelLinearFR), brakePct, lockDepth, status, barL, feedback.left());
                    System.out.flush();
                    lastLog = now;
                }
            }
        } catch (IOException e) {
            System.err.println("[!] Ошибка сокета: " + e.getMessage());
        }
    }
}