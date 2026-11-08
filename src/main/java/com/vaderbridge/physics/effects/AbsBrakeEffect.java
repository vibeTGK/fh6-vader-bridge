package com.vaderbridge.physics.effects;

import com.vaderbridge.config.BridgeConfig;
import com.vaderbridge.model.ForzaTelemetry;
import com.vaderbridge.model.TriggerFeedback;
import com.vaderbridge.physics.FeedbackEffect;

/**
 * Канонический тактильный эффект ABS из [Notebook sources] с регулировкой силы (0..100%):
 * 1. Обычное торможение (< 0.80 slip): абсолютная тишина, курок чистый и информативный.
 * 2. Блокировка колес юзом (>= 0.80 slip): четкий, резкий гидромолоток ~14 Гц (36 мс удар / 36 мс пауза).
 * 3. Контроль отрицательного скольжения всех 4 колес (защита от ложного срабатывания на буксе).
 * 4. Прямое масштабирование мощности через absPowerPercent из BridgeConfig.
 */
public class AbsBrakeEffect implements FeedbackEffect {

    private final BridgeConfig config;
    private boolean enabled = true;

    // Пороги активации
    private static final float MIN_BRAKE_PEDAL = 0.05f; // Педаль тормоза нажата более 5%
    private static final float MIN_SPEED_KMH   = 5.0f;  // Ниже 5 км/ч ABS не срабатывает

    // Проверенный период молотка ABS из блокнота (~14 Гц: 36 мс ВКЛ / 36 мс ВЫКЛ)
    private static final long PULSE_PERIOD_MS = 36;

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
        // Проверка активности торможения (если ручник затянут — ABS не стрекочет)
        if (data.brakePedal() <= MIN_BRAKE_PEDAL || data.speedKmh() < MIN_SPEED_KMH || data.handbrake()) {
            return TriggerFeedback.SILENT;
        }

        // Блокировка колес юзом — это строго отрицательное скольжение (< 0)
        float lockFL = Math.max(0.0f, -data.slipRatioFL());
        float lockFR = Math.max(0.0f, -data.slipRatioFR());
        float lockRL = Math.max(0.0f, -data.slipRatioRL());
        float lockRR = Math.max(0.0f, -data.slipRatioRR());

        // Максимальная блокировка среди всех 4 колес
        float maxLock = Math.max(Math.max(lockFL, lockFR), Math.max(lockRL, lockRR));

        // Порог срыва берем из BridgeConfig (по умолчанию 0.80..0.85)
        float threshold = (float) config.absSlipThreshold();

        // Если колеса имеют сцепление с дорогой — полная тишина
        if (maxLock <= threshold) {
            return TriggerFeedback.SILENT;
        }

        // Прерывистый импульсный цикл гидромолотка ABS (36 мс удар / 36 мс пауза)
        boolean hammerPhase = ((nowMs / PULSE_PERIOD_MS) % 2) == 0;

        if (hammerPhase) {
            // Расчет силы удара в зависимости от глубины юза (от 160 до 255)
            float depth = Math.min(1.0f, (maxLock - threshold) * 2.5f);
            int rawForce = (int) (160 + (depth * 95));

            // Применяем регулятор мощности (0..100%)
            int finalLeft = (rawForce * config.absPowerPercent()) / 100;
            finalLeft = Math.max(0, Math.min(255, finalLeft));

            // Удар строго в левый канал, правый свободен
            return new TriggerFeedback(finalLeft, 0);
        } else {
            // В фазе паузы честный ноль для тактильного отбоя клапана
            return TriggerFeedback.SILENT;
        }
    }

    @Override
    public void reset() {
    }
}