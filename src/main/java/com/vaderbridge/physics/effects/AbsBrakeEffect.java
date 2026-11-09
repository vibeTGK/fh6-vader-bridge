package com.vaderbridge.physics.effects;

import com.vaderbridge.config.BridgeConfig;
import com.vaderbridge.model.ForzaTelemetry;
import com.vaderbridge.model.TriggerFeedback;
import com.vaderbridge.physics.FeedbackEffect;

/**
 * Изолированный эффект торможения и треска ABS для левого курка (LT).
 */
public class AbsBrakeEffect implements FeedbackEffect {

    private final BridgeConfig config;
    private boolean enabled = true;

    private static final float MIN_BRAKE_PEDAL = 0.05f;  // 5% хода педали
    private static final float MIN_SPEED_KMH = 5.0f;     // Минимальная скорость для работы ABS
    private static final long PULSE_PERIOD_MS = 36;      // Частота отбойного молотка ABS (~14 Гц)

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
        if (data.brakePedal() <= MIN_BRAKE_PEDAL || data.speedKmh() < MIN_SPEED_KMH) {
            return TriggerFeedback.SILENT;
        }

        // Продольное скольжение передней оси
        float frontSlip = Math.max(Math.abs(data.slipRatioFL()), Math.abs(data.slipRatioFR()));

        if (frontSlip > config.absSlipThreshold()) {
            // Прерывистый импульсный цикл гидроклапанов ABS
            boolean hammerPhase = ((nowMs / PULSE_PERIOD_MS) % 2) == 0;
            if (hammerPhase) {
                double depth = Math.min(1.0, (frontSlip - config.absSlipThreshold()) * 2.2);
                int ltForce = (int) (160 + (depth * 95)); // От 160 до 255
                return new TriggerFeedback(ltForce, 0);
            }
        }

        return TriggerFeedback.SILENT;
    }
}