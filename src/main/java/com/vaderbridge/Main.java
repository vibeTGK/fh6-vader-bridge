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

    private static final float TYRE_RADIUS_METERS = 0.33f; // Стандартный радиус колеса спорткара

    public static void main(String[] args) {
        BridgeConfig config = BridgeConfig.fromArgs(args);

        System.out.println("==========================================================================================");
        System.out.println(" Flydigi Vader 4 Pro -> Телеметрия ABS и физики колес (Аппаратный поток 500 Гц)");
        System.out.printf(" Режим: ABS [%s, Сила: %d%%] | Помощники игры: ВЫКЛ\n",
                config.absEnabled() ? "ВКЛ" : "ВЫКЛ", config.absPowerPercent());
        System.out.println("==========================================================================================");

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
            System.out.println("[*] ЛОГ: Скорость авто, скорость колес, продольный срыв, боковой угол и моторы:\n");

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
                    float brakePct = telemetry.brakePedal() * 100.0f;

                    // 1. Линейная скорость передних колес (км/ч)
                    float wheelSpdFL = Math.max(0.0f, telemetry.wheelSpeedFL() * TYRE_RADIUS_METERS * 3.6f);
                    float wheelSpdFR = Math.max(0.0f, telemetry.wheelSpeedFR() * TYRE_RADIUS_METERS * 3.6f);

                    // 2. Реальный процент тормозного клина (на сколько медленнее машины крутится колесо)
                    int lockFL = 0;
                    int lockFR = 0;
                    if (carSpeed > 5.0f) {
                        lockFL = (int) Math.max(0, Math.min(100, (1.0f - (wheelSpdFL / carSpeed)) * 100.0f));
                        lockFR = (int) Math.max(0, Math.min(100, (1.0f - (wheelSpdFR / carSpeed)) * 100.0f));
                    }

                    // 3. Продольный срыв шин (отрицательный slip ratio)
                    float slipFL = telemetry.slipRatioFL();
                    float slipFR = telemetry.slipRatioFR();

                    // 4. Боковой угол увода (снос передка в радианах -> переводим в градусы)
                    float angleFL = (float) Math.toDegrees(telemetry.slipAngleFL());
                    float angleFR = (float) Math.toDegrees(telemetry.slipAngleFR());

                    // Вывод в одну четкую диагностическую строку
                    System.out.printf("\r[Авто: %5.1f км/ч | LT: %3.0f%%] | Колеса(FL/FR): %5.1f / %5.1f км/ч | Клин: %3d%% / %3d%% | SlipRatio: %+5.2f / %+5.2f | Снос: %4.1f° | L: %3d | R: %3d",
                            carSpeed,
                            brakePct,
                            wheelSpdFL, wheelSpdFR,
                            lockFL, lockFR,
                            slipFL, slipFR,
                            Math.max(Math.abs(angleFL), Math.abs(angleFR)),
                            feedback.left(),
                            feedback.right());
                    System.out.flush();
                    lastLog = now;
                }
            }
        } catch (IOException e) {
            System.err.println("[!] Ошибка сокета: " + e.getMessage());
        }
    }
}