package com.vaderbridge.physics.effects;

import com.vaderbridge.config.BridgeConfig;
import com.vaderbridge.model.ForzaTelemetry;
import com.vaderbridge.model.TriggerFeedback;
import com.vaderbridge.physics.FeedbackEffect;

/**
 * Профессиональный эффект блокировки колес (SimHub Wheel Lock Architecture):
 * 1. Защита от сноса: боковое скольжение в поворотах игнорируется.
 * 2. Анализ реального физического клина: сравнивает скорость машины со скоростью вращения колеса (WheelRotationSpeed).
 * 3. Срабатывает ТОЛЬКО тогда, когда колесо реально остановилось от тормоза (Lockup).
 * 4. Высокоинформативная пульсация ~28 Гц (18 мс импульс / 18 мс пауза) с умеренной силой,
 *    которая помогает пилоту контролировать грань, а не глушит руки.
 */
public class AbsBrakeEffect implements FeedbackEffect {

    private final BridgeConfig config;
    private boolean enabled = true;

    private static final float MIN_BRAKE_PEDAL = 0.12f; // Педаль нажата более 12%
    private static final float MIN_SPEED_KMH   = 15.0f; // Ниже 15 км/ч эффект отключается

    // Средний эффективный радиус качения колеса спорткара (в метрах)
    private static final float TYRE_RADIUS_METERS = 0.33f;

    // Тайминги спортивной тактильной трещотки SimHub (~28 Гц) под шину 500 Гц (шаг 2 мс)
    private static final long PULSE_HALF_MS = 18;

    public AbsBrakeEffect(BridgeConfig config) {
        this.config = config;
    }

    @Override
    public String getName() {
        return "ABS";
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
        float carSpeedKmh = data.speedKmh();

        // Если тормоз не нажат, машина едет медленно или затянут ручник — полная тишина
        if (data.brakePedal() < MIN_BRAKE_PEDAL || carSpeedKmh < MIN_SPEED_KMH || data.handbrake()) {
            return TriggerFeedback.SILENT;
        }

        // Переводим угловую скорость вращения передних колес (рад/с) в линейную скорость (км/ч):
        // V = omega * R * 3.6
        float wheelLinearFL = Math.max(0.0f, data.wheelSpeedFL() * TYRE_RADIUS_METERS * 3.6f);
        float wheelLinearFR = Math.max(0.0f, data.wheelSpeedFR() * TYRE_RADIUS_METERS * 3.6f);

        // Истинная блокировка (True Lock): насколько медленнее крутится колесо относительно машины
        // 0.0 = колесо катится со скоростью дороги (зацеп).
        // 1.0 = колесо встало колом (0 км/ч), пока машина летит вперед.
        float lockFL = 1.0f - (wheelLinearFL / carSpeedKmh);
        float lockFR = 1.0f - (wheelLinearFR / carSpeedKmh);

        float maxLock = Math.max(lockFL, lockFR);

        // 1. КОЛЕСА ВРАЩАЮТСЯ (< 0.40): АБСОЛЮТНАЯ ТИШИНА!
        // Даже если машина в легком сносе боком, пока колеса крутятся — в руках тишина!
        if (maxLock < 0.40f) {
            return TriggerFeedback.SILENT;
        }

        // 2. РЕАЛЬНЫЙ КЛИН ОТ ТОРМОЗА (>= 0.40):
        // Высокочастотный тактильный отклик блокировки (как в педалях SimHub / Fanatec V3)
        boolean pulsePhase = ((nowMs / PULSE_HALF_MS) % 2) == 0;

        int rawLeft;
        if (pulsePhase) {
            // Глубина блокировки: от 40% до 85% проскальзывания
            float lockProgress = (maxLock - 0.40f) / 0.45f;
            lockProgress = Math.max(0.0f, Math.min(1.0f, lockProgress));

            // Сила от 100 до 200 (не глушит руки в 255, дает чистую спортивную информацию)
            rawLeft = (int) (100 + lockProgress * 100);
        } else {
            rawLeft = 0;
        }

        // Масштабирование через регулятор силы BridgeConfig (0..100%)
        int finalLeft = (rawLeft * config.absPowerPercent()) / 100;
        finalLeft = Math.max(0, Math.min(255, finalLeft));

        return new TriggerFeedback(finalLeft, 0);
    }
123412
    @Override
    public void reset() {
    }
}