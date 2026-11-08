package com.vaderbridge.physics;

import com.vaderbridge.config.BridgeConfig;
import com.vaderbridge.model.ForzaTelemetry;
import com.vaderbridge.model.TriggerFeedback;
import com.vaderbridge.physics.effects.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class HapticFeedbackCalculator {

    private final List<FeedbackEffect> effects = new ArrayList<>();

    public HapticFeedbackCalculator(BridgeConfig config) {
        // 1. ABS (Тормоз)
        if (config.absEnabled()) {
            AbsBrakeEffect abs = new AbsBrakeEffect(config);
            abs.setEnabled(true);
            registerEffect(abs);
        }

        // 2. Пробуксовка (Газ)
        if (config.tractionEnabled()) {
            TractionSlipEffect traction = new TractionSlipEffect(config);
            traction.setEnabled(true);
            registerEffect(traction);
        }

        // 3. Удар КПП
        if (config.gearShiftKickEnabled()) {
            GearShiftKickEffect shift = new GearShiftKickEffect();
            shift.setEnabled(true);
            registerEffect(shift);
        }

        // 4. Отсечка оборотов
        if (config.revLimiterEnabled()) {
            RevLimiterEffect revLimiter = new RevLimiterEffect();
            revLimiter.setEnabled(true);
            registerEffect(revLimiter);
        }

        // 5. Поребрики
        if (config.rumbleStripEnabled()) {
            RumbleStripEffect rumble = new RumbleStripEffect();
            rumble.setEnabled(true);
            registerEffect(rumble);
        }
    }

    public void registerEffect(FeedbackEffect effect) {
        effects.add(effect);
    }

    public List<FeedbackEffect> getEffects() {
        return Collections.unmodifiableList(effects);
    }

    public TriggerFeedback calculate(ForzaTelemetry data) {
        if (data == null || !data.isRaceOn() || effects.isEmpty()) {
            return TriggerFeedback.SILENT;
        }

        long now = System.currentTimeMillis();
        int maxLeft = 0;
        int maxRight = 0;

        for (FeedbackEffect effect : effects) {
            if (!effect.isEnabled()) {
                continue;
            }

            TriggerFeedback fb = effect.calculate(data, now);
            if (fb.left() > maxLeft) maxLeft = fb.left();
            if (fb.right() > maxRight) maxRight = fb.right();
        }

        return new TriggerFeedback(maxLeft, maxRight);
    }
}