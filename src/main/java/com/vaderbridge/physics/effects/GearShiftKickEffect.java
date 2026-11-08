package com.vaderbridge.physics.effects;

import com.vaderbridge.model.ForzaTelemetry;
import com.vaderbridge.model.TriggerFeedback;
import com.vaderbridge.physics.FeedbackEffect;

/**
 * Сбалансированный эффект коробки передач с компенсацией асимметрии эксцентриков:
 * - Левый мотор (Heavy) аппаратно демпфируется (~0.60), чтобы не заглушать правый мотор.
 * - Переключения вверх (Upshift) и вниз (Downshift) выровнены по суммарной тактильной энергии.
 * - Двухфазная волна гашения инерции для устранения паразитного послегула.
 */
public class GearShiftKickEffect implements FeedbackEffect {

    private boolean enabled = true;
    private int previousGear = -1;
    private long shiftStartTimeMs = 0;
    private boolean isUpshift = true;
    private float shiftIntensity = 1.0f;

    // Тайминги клика: 30 мс атака + 40 мс спад
    private static final long ATTACK_PHASE_MS = 75;
    private static final long DECAY_PHASE_MS = 130;

    // Коэффициент компенсации массы левого тяжелого эксцентрика
    private static final float HEAVY_MOTOR_WEIGHT_FACTOR = 0.99f;

    @Override
    public String getName() {
        return "GearShiftKick";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public TriggerFeedback calculate(ForzaTelemetry data, long nowMs) {
        int currentGear = data.gear();

        // Фиксируем смену передачи на реальной скорости
        if (previousGear != -1 && currentGear != previousGear && data.speedKmh() > 10.0f) {
            if (currentGear > 0 && previousGear > 0) {
                shiftStartTimeMs = nowMs;
                isUpshift = currentGear > previousGear;

                float rpmRatio = (data.engineMaxRpm() > 1000.0f)
                        ? (data.currentEngineRpm() / data.engineMaxRpm())
                        : 0.5f;
                rpmRatio = Math.max(0.2f, Math.min(1.0f, rpmRatio));

                if (isUpshift) {
                    // Разгон: масштабируем от газа и оборотов (0.50 .. 1.00)
                    float throttle = Math.max(0.2f, data.accelPedal());
                    shiftIntensity = Math.min(1.0f, 0.45f + (rpmRatio * throttle * 0.55f));
                } else {
                    // Торможение двигателем: плотный стабильный щелчок (0.65 .. 0.85)
                    shiftIntensity = Math.min(0.85f, 0.40f + (rpmRatio * 0.45f));
                }
            }
        }
        previousGear = currentGear;

        long elapsed = nowMs - shiftStartTimeMs;
        if (elapsed >= DECAY_PHASE_MS) {
            return TriggerFeedback.SILENT;
        }

        // Огибающая затухания
        float envelope;
        if (elapsed < ATTACK_PHASE_MS) {
            envelope = 1.0f;
        } else {
            float decayProgress = (float) (elapsed - ATTACK_PHASE_MS) / (DECAY_PHASE_MS - ATTACK_PHASE_MS);
            envelope = 1.0f - (decayProgress * 0.75f);
        }

        int basePower = (int) (255 * shiftIntensity * envelope);
        basePower = Math.max(0, Math.min(255, basePower));

        if (isUpshift) {
            // ВВЕРХ: быстрый легкий эксцентрик бьет на 100%, тяжелый помогает на 58%
            int leftMotor = (int) (basePower * HEAVY_MOTOR_WEIGHT_FACTOR);
            int rightMotor = basePower;
            return new TriggerFeedback(leftMotor, rightMotor);
        } else {
            // ВНИЗ: тяжелый мотор ограничен, чтобы не глушить ладонь, правый подщелкивает
            int leftMotor = (int) (basePower * HEAVY_MOTOR_WEIGHT_FACTOR * 0.90f);
            int rightMotor = (int) (basePower * 0.65f);
            return new TriggerFeedback(leftMotor, rightMotor);
        }
    }

    @Override
    public void reset() {
        previousGear = -1;
        shiftStartTimeMs = 0;
    }
}