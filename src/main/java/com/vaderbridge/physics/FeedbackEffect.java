package com.vaderbridge.physics;

import com.vaderbridge.model.ForzaTelemetry;
import com.vaderbridge.model.TriggerFeedback;

/**
 * Базовый контракт для изолированного тактильного эффекта.
 * Позволяет независимо включать/выключать и настраивать каждый эффект.
 */
public interface FeedbackEffect {

    /**
     * Уникальное имя эффекта для логирования и конфигурации.
     */
    String getName();

    /**
     * Флаг активности эффекта. Если false — калькулятор полностью пропускает этот эффект.
     */
    boolean isEnabled();

    /**
     * Включение или выключение эффекта на лету.
     */
    void setEnabled(boolean enabled);

    /**
     * Расчёт тактильного отклика для данного кадра телеметрии.
     *
     * @param telemetry Данные от игры Forza
     * @param nowMs     Текущее системное время в миллисекундах
     * @return TriggerFeedback со значениями [0..255] для левой (LT) и правой (RT) стороны
     */
    TriggerFeedback calculate(ForzaTelemetry telemetry, long nowMs);

    /**
     * Сброс внутреннего состояния (таймеры, предыдущие передачи, сглаживание).
     */
    default void reset() {}
}