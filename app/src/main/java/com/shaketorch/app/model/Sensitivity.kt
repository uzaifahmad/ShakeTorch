package com.shaketorch.app.model

/**
 * Represents the shake gesture sensitivity levels.
 *
 * @property threshold Acceleration magnitude threshold in m/s^2 above gravity.
 * @property timeWindowMs Maximum time window in milliseconds between double chop gestures.
 * @property label Human readable label.
 * @property description User facing explanation of the sensitivity setting.
 */
enum class Sensitivity(
    val threshold: Float,
    val timeWindowMs: Long,
    val label: String,
    val description: String
) {
    LOW(
        threshold = 14.0f,
        timeWindowMs = 1200L,
        label = "Low",
        description = "Requires lighter movement. Responsive but may trigger more easily."
    ),
    MEDIUM(
        threshold = 18.0f,
        timeWindowMs = 1000L,
        label = "Medium",
        description = "Balanced setting. Ideal for everyday use with minimal false triggers."
    ),
    HIGH(
        threshold = 24.0f,
        timeWindowMs = 850L,
        label = "High",
        description = "Requires strong, deliberate chop gesture. Prevents accidental toggles."
    );

    companion object {
        fun fromName(name: String?): Sensitivity {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: MEDIUM
        }
    }
}
