package com.vaderbridge.telemetry;

import com.vaderbridge.model.ForzaTelemetry;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Высокопроизводительный бинарный парсер пакетов Forza Data Out (Sled 232 байта / Dash 324 байта).
 * Полностью согласован с моделью ForzaTelemetry.
 */
public class ForzaPacketParser {

    private static final int SLED_MIN_SIZE = 232;
    private static final int DASH_HORIZON_SIZE = 324;

    public ForzaTelemetry parse(byte[] data, int length) {
        if (data == null || length < SLED_MIN_SIZE) {
            return null;
        }

        ByteBuffer bb = ByteBuffer.wrap(data, 0, length);
        bb.order(ByteOrder.LITTLE_ENDIAN);

        // 1. Статус заезда и временная метка (Offset 0..7)
        boolean isRaceOn = bb.getInt(0) != 0;
        long timestampMs = bb.getInt(4) & 0xFFFFFFFFL;

        // 2. Двигатель (Offset 8..19)
        float maxRpm = sanitize(bb.getFloat(8));
        float idleRpm = sanitize(bb.getFloat(12));
        float currentRpm = sanitize(bb.getFloat(16));

        // 3. Ход подвески FL, FR, RL, RR (Offset 68..83)
        // 0.0 = вывешена в воздухе, 1.0 = пробой отбойника
        float suspFL = sanitize(bb.getFloat(68));
        float suspFR = sanitize(bb.getFloat(72));
        float suspRL = sanitize(bb.getFloat(76));
        float suspRR = sanitize(bb.getFloat(80));

        // 4. Продольное скольжение шин FL, FR, RL, RR (Offset 84..99)
        // > 0 = пробуксовка (газ), < 0 = юз/блокировка (ABS)
        float slipRatioFL = sanitize(bb.getFloat(84));
        float slipRatioFR = sanitize(bb.getFloat(88));
        float slipRatioRL = sanitize(bb.getFloat(92));
        float slipRatioRR = sanitize(bb.getFloat(96));

        // 5. Поребрики апекса (Offset 116..123)
        float curbFL = sanitize(bb.getFloat(116));
        float curbFR = sanitize(bb.getFloat(120));

        // 6. Шероховатость покрытия: гравий, брусчатка, грунт (Offset 148..155)
        float surfFL = sanitize(bb.getFloat(148));
        float surfFR = sanitize(bb.getFloat(152));

        // 7. Боковой угол увода шин FL, FR, RL, RR (Offset 164..179)
        // FL/FR = снос передка (Understeer), RL/RR = занос кормы (Oversteer)
        float slipAngleFL = sanitize(bb.getFloat(164));
        float slipAngleFR = sanitize(bb.getFloat(168));
        float slipAngleRL = sanitize(bb.getFloat(172));
        float slipAngleRR = sanitize(bb.getFloat(176));

        // 8. Данные трансмиссии и ID авто (Offset 212..227)
        int carOrdinal = bb.getInt(212);
        int drivetrain = bb.getInt(224); // 0 = FWD, 1 = RWD, 2 = AWD

        // 9. Секция DASH: органы управления и точная скорость (Offset 244..323)
        float speedKmh;
        float accelPedal = 0.0f;
        float brakePedal = 0.0f;
        float clutchPedal = 0.0f;
        boolean handbrake = false;
        int gear = 1;

        if (length >= DASH_HORIZON_SIZE) {
            // Скорость в м/с (Offset 256) -> переводим в км/ч
            float speedMps = sanitize(bb.getFloat(256));
            speedKmh = Math.max(0.0f, speedMps * 3.6f);

            // Педали и управление (Offset 315..320)
            accelPedal = (bb.get(315) & 0xFF) / 255.0f;
            brakePedal = (bb.get(316) & 0xFF) / 255.0f;
            clutchPedal = (bb.get(317) & 0xFF) / 255.0f;
            handbrake = (bb.get(318) & 0xFF) > 0;
            gear = bb.get(319) & 0xFF; // 0=R, 1=N, 2=1st, 3=2nd...
        } else {
            // Fallback для коротких пакетов Sled (232 байта):
            // Считаем скорость по трехмерному вектору Velocity (Offset 32, 36, 40)
            float vx = sanitize(bb.getFloat(32));
            float vy = sanitize(bb.getFloat(36));
            float vz = sanitize(bb.getFloat(40));
            speedKmh = (float) (Math.sqrt(vx * vx + vy * vy + vz * vz) * 3.6);

            // Приблизительная оценка газа по оборотам
            accelPedal = (currentRpm > idleRpm + 800.0f) ? 1.0f : 0.0f;
            brakePedal = (slipRatioFL < -0.3f || slipRatioFR < -0.3f) ? 1.0f : 0.0f;
        }

        return new ForzaTelemetry(
                isRaceOn,
                timestampMs,
                maxRpm,
                idleRpm,
                currentRpm,
                speedKmh,
                accelPedal,
                brakePedal,
                clutchPedal,
                handbrake,
                gear,
                drivetrain,
                carOrdinal,
                slipRatioFL,
                slipRatioFR,
                slipRatioRL,
                slipRatioRR,
                slipAngleFL,
                slipAngleFR,
                slipAngleRL,
                slipAngleRR,
                suspFL,
                suspFR,
                suspRL,
                suspRR,
                curbFL,
                curbFR,
                surfFL,
                surfFR
        );
    }

    private static float sanitize(float value) {
        return (Float.isNaN(value) || Float.isInfinite(value)) ? 0.0f : value;
    }
}