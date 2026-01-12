package com.kokeshi.metersnap.utils

import android.util.Log

/**
 * ReadingValidator - Decision Algorithm for Meter Reading Validation
 * 
 * This class implements validation logic to prevent impossible readings and
 * provide multi-detector voting for robust meter recognition.
 * 
 * Key Decision Rules:
 * 1. Max Monthly Usage Limit (realistic consumption bounds)
 * 2. Digit Count Validation (5 digits max for standard water meters)
 * 3. Detector Voting System (LeNet vs MLKit vs Hybrid consensus)
 * 4. Noise Detection (leading/trailing digit anomalies)
 */
object ReadingValidator {
    
    private const val TAG = "ReadingValidator"
    
    // ===== CONFIGURABLE THRESHOLDS =====
    
    /**
     * Maximum reasonable monthly water usage in cubic meters (m³).
     * 
     * Context:
     * - Average household: 10-30 m³/month
     * - Large family: 50-80 m³/month
     * - Small business: 100-200 m³/month
     * - Large commercial: 300-500 m³/month
     * - Industrial (extreme): 500-1000 m³/month
     * 
     * We set 999 m³ as the absolute upper bound - anything beyond this
     * in a single month is almost certainly a detection error.
     */
    const val MAX_MONTHLY_USAGE = 999
    
    /**
     * Warning threshold for high usage (prompts user confirmation).
     * Above this, the reading might be valid but warrants double-checking.
     */
    const val HIGH_USAGE_WARNING_THRESHOLD = 200
    
    /**
     * Maximum digits for a standard residential water meter.
     * Most meters have 5 black digits (whole m³) + red decimals.
     */
    const val MAX_DIGIT_COUNT = 5
    
    /**
     * Minimum digits expected for a valid reading.
     */
    const val MIN_DIGIT_COUNT = 4
    
    /**
     * Maximum allowed reading value (99999 for 5-digit meters).
     */
    const val MAX_READING_VALUE = 99999
    
    // ===== NORMALIZATION =====
    
    /**
     * Normalizes a reading string for comparison.
     * "04525", "4525", "004525" all become "4525" (the numeric value as string).
     * This prevents the voting system from treating equivalent readings as different.
     */
    fun normalizeReading(reading: String): String {
        if (reading.isBlank()) return ""
        val numeric = reading.toIntOrNull() ?: return reading
        return numeric.toString()
    }
    
    /**
     * Checks if two readings represent the same numeric value.
     * "04525" == "4525" == "004525" → true
     */
    fun areReadingsEquivalent(reading1: String, reading2: String): Boolean {
        if (reading1.isBlank() || reading2.isBlank()) return false
        val num1 = reading1.toIntOrNull() ?: return false
        val num2 = reading2.toIntOrNull() ?: return false
        return num1 == num2
    }
    
    /**
     * Formats a reading to standard 5-digit display format with leading zeros.
     * 4525 → "04525"
     */
    fun formatReading(reading: String): String {
        val numeric = reading.toIntOrNull() ?: return reading
        return String.format("%05d", numeric)
    }
    
    // ===== VALIDATION RESULT =====
    
    data class ValidationResult(
        val isValid: Boolean,
        val cleanedReading: String,
        val errorCode: ErrorCode,
        val message: String,
        val suggestedAction: SuggestedAction,
        val confidence: Float = 0f
    )
    
    enum class ErrorCode {
        VALID,
        IMPOSSIBLE_USAGE,           // Reading implies usage > MAX_MONTHLY_USAGE
        HIGH_USAGE_WARNING,         // Reading implies high but possible usage
        READING_TOO_LOW,            // Current < Previous (meter can't go backwards)
        TOO_MANY_DIGITS,            // More than 5 digits detected
        TOO_FEW_DIGITS,             // Less than 4 digits detected
        READING_OVERFLOW,           // Reading exceeds max meter capacity
        DETECTOR_DISAGREEMENT,      // LeNet, MLKit, Hybrid all disagree
        LEADING_NOISE_DETECTED,     // First digit is likely noise
        TRAILING_NOISE_DETECTED,    // Last digit is likely noise
        EMPTY_READING,              // Nothing detected
        PARSE_ERROR                 // Can't parse as number
    }
    
    enum class SuggestedAction {
        ACCEPT,                     // Good to go
        CONFIRM_HIGH_USAGE,         // Ask user to confirm high reading
        RETRY_SCAN,                 // Try again with current framing
        ADJUST_POSITION,            // Move camera closer/further
        MANUAL_INPUT,               // Give up, let user type it
        CHECK_PREVIOUS_READING      // Previous reading might be wrong
    }
    
    // ===== MAIN VALIDATION FUNCTION =====
    
    /**
     * Validates a detected meter reading against the previous reading.
     * 
     * @param detectedReading The raw string from the detector (e.g., "01616")
     * @param previousReading The last known meter reading (from storage)
     * @return ValidationResult with status, cleaned reading, and suggestions
     */
    fun validateReading(
        detectedReading: String,
        previousReading: Int
    ): ValidationResult {
        Log.d(TAG, "Validating: detected='$detectedReading', previous=$previousReading")
        
        // 1. Empty check
        if (detectedReading.isBlank()) {
            return ValidationResult(
                isValid = false,
                cleanedReading = "",
                errorCode = ErrorCode.EMPTY_READING,
                message = "No reading detected",
                suggestedAction = SuggestedAction.RETRY_SCAN
            )
        }
        
        // 2. Digit count check (too many digits = noise)
        if (detectedReading.length > MAX_DIGIT_COUNT) {
            val cleaned = tryRemoveNoise(detectedReading, previousReading)
            if (cleaned != null) {
                // Recursively validate the cleaned reading
                val revalidated = validateReading(cleaned, previousReading)
                if (revalidated.isValid) {
                    return revalidated.copy(
                        message = "Removed noise digit: ${revalidated.cleanedReading}"
                    )
                }
            }
            return ValidationResult(
                isValid = false,
                cleanedReading = detectedReading,
                errorCode = ErrorCode.TOO_MANY_DIGITS,
                message = "Detected ${detectedReading.length} digits (max $MAX_DIGIT_COUNT). Likely noise at start or end.",
                suggestedAction = SuggestedAction.ADJUST_POSITION
            )
        }
        
        // 3. Parse to integer
        val currentReading = detectedReading.toIntOrNull()
        if (currentReading == null) {
            return ValidationResult(
                isValid = false,
                cleanedReading = detectedReading,
                errorCode = ErrorCode.PARSE_ERROR,
                message = "Could not parse '$detectedReading' as a number",
                suggestedAction = SuggestedAction.RETRY_SCAN
            )
        }
        
        // 4. Too few digits (might be partial read)
        if (detectedReading.length < MIN_DIGIT_COUNT) {
            return ValidationResult(
                isValid = false,
                cleanedReading = detectedReading,
                errorCode = ErrorCode.TOO_FEW_DIGITS,
                message = "Only ${detectedReading.length} digits detected. Move closer to the meter.",
                suggestedAction = SuggestedAction.ADJUST_POSITION
            )
        }
        
        // 5. Overflow check
        if (currentReading > MAX_READING_VALUE) {
            return ValidationResult(
                isValid = false,
                cleanedReading = detectedReading,
                errorCode = ErrorCode.READING_OVERFLOW,
                message = "Reading $currentReading exceeds meter capacity",
                suggestedAction = SuggestedAction.RETRY_SCAN
            )
        }
        
        // 6. Backwards check (current < previous)
        if (currentReading < previousReading) {
            return ValidationResult(
                isValid = false,
                cleanedReading = detectedReading,
                errorCode = ErrorCode.READING_TOO_LOW,
                message = "Current ($currentReading) is less than previous ($previousReading). Meter can't go backwards!",
                suggestedAction = SuggestedAction.CHECK_PREVIOUS_READING
            )
        }
        
        // 7. Calculate usage and check bounds
        val usage = currentReading - previousReading
        
        if (usage > MAX_MONTHLY_USAGE) {
            // Try to fix by removing potential noise
            val cleaned = tryRemoveNoise(detectedReading, previousReading)
            if (cleaned != null && cleaned != detectedReading) {
                val revalidated = validateReading(cleaned, previousReading)
                if (revalidated.isValid || revalidated.errorCode == ErrorCode.HIGH_USAGE_WARNING) {
                    return revalidated.copy(
                        message = "Auto-corrected noise: $detectedReading → ${revalidated.cleanedReading}"
                    )
                }
            }
            
            return ValidationResult(
                isValid = false,
                cleanedReading = detectedReading,
                errorCode = ErrorCode.IMPOSSIBLE_USAGE,
                message = "Impossible usage: $usage m³/month (max realistic: $MAX_MONTHLY_USAGE). Previous: $previousReading, Detected: $currentReading",
                suggestedAction = SuggestedAction.RETRY_SCAN
            )
        }
        
        // 8. High usage warning (valid but suspicious)
        if (usage > HIGH_USAGE_WARNING_THRESHOLD) {
            return ValidationResult(
                isValid = true, // Still valid, but flagged
                cleanedReading = detectedReading,
                errorCode = ErrorCode.HIGH_USAGE_WARNING,
                message = "High usage detected: $usage m³. Please confirm this is correct.",
                suggestedAction = SuggestedAction.CONFIRM_HIGH_USAGE,
                confidence = 70f
            )
        }
        
        // 9. All checks passed!
        return ValidationResult(
            isValid = true,
            cleanedReading = detectedReading,
            errorCode = ErrorCode.VALID,
            message = "Valid reading: $currentReading (usage: $usage m³)",
            suggestedAction = SuggestedAction.ACCEPT,
            confidence = 100f
        )
    }
    
    // ===== NOISE REMOVAL =====
    
    /**
     * Attempts to fix a reading by removing a likely noise digit.
     * 
     * Strategy:
     * 1. If 6 digits: Try removing first OR last digit
     * 2. Pick the version that makes the most sense given previous reading
     * 
     * Example: Previous=1575, Detected=401616
     *   - Remove first (4): 01616 → 1616 → usage = 41 ✓ (reasonable)
     *   - Remove last (6): 40161 → 40161 → usage = 38586 ✗ (impossible)
     *   - Winner: Remove first → 01616
     */
    private fun tryRemoveNoise(reading: String, previousReading: Int): String? {
        if (reading.length <= MAX_DIGIT_COUNT) return null
        
        val candidates = mutableListOf<Pair<String, Int>>() // (cleaned reading, usage)
        
        // Try removing first digit
        val withoutFirst = reading.substring(1)
        val usageFirst = (withoutFirst.toIntOrNull() ?: Int.MAX_VALUE) - previousReading
        if (usageFirst >= 0 && usageFirst <= MAX_MONTHLY_USAGE) {
            candidates.add(withoutFirst to usageFirst)
        }
        
        // Try removing last digit
        val withoutLast = reading.substring(0, reading.length - 1)
        val usageLast = (withoutLast.toIntOrNull() ?: Int.MAX_VALUE) - previousReading
        if (usageLast >= 0 && usageLast <= MAX_MONTHLY_USAGE) {
            candidates.add(withoutLast to usageLast)
        }
        
        // Pick the candidate with the most reasonable usage (closest to typical)
        // Typical household usage is around 15-30 m³, so we prefer values closer to that
        val typicalUsage = 25
        val best = candidates.minByOrNull { kotlin.math.abs(it.second - typicalUsage) }
        
        return best?.first
    }
    
    // ===== MULTI-DETECTOR VOTING =====
    
    /**
     * Result from the voting system across multiple detectors.
     */
    data class VotingResult(
        val consensusReading: String?,
        val confidence: Float,
        val agreementLevel: AgreementLevel,
        val details: String
    )
    
    enum class AgreementLevel {
        UNANIMOUS,      // All detectors agree perfectly
        MAJORITY,       // 2 out of 3 agree
        PREFIX_MATCH,   // First 4 digits match, last digit differs (rolling digit)
        NO_CONSENSUS    // Complete disagreement
    }
    
    /**
     * Compares readings from LeNet, MLKit, and Hybrid approaches.
     * Uses voting to determine the most likely correct reading.
     * 
     * IMPORTANT: Uses NORMALIZED comparison so "04525" and "4525" are treated as equal!
     * 
     * @param lenetReading Reading from pure LeNet classification
     * @param mlkitReading Reading from Google ML Kit text recognition
     * @param hybridReading Reading from ML Kit localization + LeNet classification
     * @param previousReading Previous meter reading for validation
     * @return VotingResult with consensus and confidence
     */
    fun voteOnReading(
        lenetReading: String,
        mlkitReading: String,
        hybridReading: String,
        previousReading: Int
    ): VotingResult {
        Log.d(TAG, "Voting: LeNet='$lenetReading', MLKit='$mlkitReading', Hybrid='$hybridReading'")
        
        // Filter and normalize readings
        val readings = listOf(
            "LeNet" to lenetReading,
            "MLKit" to mlkitReading,
            "Hybrid" to hybridReading
        ).filter { it.second.isNotBlank() }
            .map { (source, reading) -> source to normalizeReading(reading) }
        
        if (readings.isEmpty()) {
            return VotingResult(
                consensusReading = null,
                confidence = 0f,
                agreementLevel = AgreementLevel.NO_CONSENSUS,
                details = "No valid readings from any detector"
            )
        }
        
        // Case 1: Check for unanimous agreement (using NORMALIZED values!)
        val normalizedValues = readings.map { it.second }.distinct()
        if (normalizedValues.size == 1 && readings.size >= 2) {
            // All agree! Return formatted version (with leading zeros)
            val formatted = formatReading(normalizedValues.first())
            
            // BOOST if valid against previous reading!
            val validation = validateReading(formatted, previousReading)
            val baseConfidence = if (validation.isValid && validation.errorCode == ErrorCode.VALID) {
                100f  // Perfect!
            } else if (validation.isValid) {
                95f   // Valid but high usage
            } else {
                90f   // All detectors agree but doesn't match previous - still trust it
            }
            
            return VotingResult(
                consensusReading = formatted,
                confidence = baseConfidence,
                agreementLevel = AgreementLevel.UNANIMOUS,
                details = "All ${readings.size} detectors agree: $formatted"
            )
        }
        
        // Case 2: Check for majority (2/3 agree) using NORMALIZED comparison
        val frequencyMap = readings.groupingBy { it.second }.eachCount()
        val majority = frequencyMap.filter { it.value >= 2 }.maxByOrNull { it.value }
        if (majority != null) {
            val agreers = readings.filter { it.second == majority.key }.map { it.first }
            val formatted = formatReading(majority.key)
            
            // BOOST if valid against previous reading!
            val validation = validateReading(formatted, previousReading)
            val baseConfidence = if (validation.isValid && validation.errorCode == ErrorCode.VALID) {
                95f  // Majority + valid = very confident!
            } else if (validation.isValid) {
                90f  // Majority + high usage warning
            } else {
                80f  // Majority agree but doesn't validate
            }
            
            return VotingResult(
                consensusReading = formatted,
                confidence = baseConfidence,
                agreementLevel = AgreementLevel.MAJORITY,
                details = "Majority: ${agreers.joinToString(" & ")} agree on $formatted"
            )
        }
        
        // Case 3: Check prefix match (first 4 digits same, last differs - rolling digit scenario)
        if (readings.size >= 2) {
            // Use normalized readings for prefix comparison
            val prefixes = readings.map { formatReading(it.second).take(4) to it }
            val prefixGroups = prefixes.groupBy { it.first }.filter { it.value.size >= 2 }
            
            if (prefixGroups.isNotEmpty()) {
                val bestPrefix = prefixGroups.maxByOrNull { it.value.size }!!
                
                // Among readings with same prefix, pick the one most consistent with previous
                val validatedCandidates = bestPrefix.value.mapNotNull { (_, pair) ->
                    val formatted = formatReading(pair.second)
                    val validation = validateReading(formatted, previousReading)
                    if (validation.isValid) formatted to validation else null
                }
                
                val winner = validatedCandidates.maxByOrNull { it.second.confidence }?.first
                    ?: formatReading(bestPrefix.value.first().second.second)
                
                // BOOST if valid against previous reading!
                val validation = validateReading(winner, previousReading)
                val baseConfidence = if (validation.isValid && validation.errorCode == ErrorCode.VALID) {
                    90f  // Prefix match + valid
                } else if (validation.isValid) {
                    85f  // Prefix match + high usage
                } else {
                    70f  // Prefix match but doesn't validate
                }
                
                return VotingResult(
                    consensusReading = winner,
                    confidence = baseConfidence,
                    agreementLevel = AgreementLevel.PREFIX_MATCH,
                    details = "Prefix match (${bestPrefix.key}****), selected: $winner"
                )
            }
        }
        
        // Case 4: No consensus - pick the most valid one
        val validatedReadings = readings.mapNotNull { (source, reading) ->
            val formatted = formatReading(reading)
            val validation = validateReading(formatted, previousReading)
            if (validation.isValid) Triple(source, formatted, validation) else null
        }
        
        if (validatedReadings.isNotEmpty()) {
            val best = validatedReadings.maxByOrNull { it.third.confidence }!!
            
            // Even with no consensus, if it validates, give decent confidence
            val baseConfidence = if (best.third.errorCode == ErrorCode.VALID) {
                75f  // No consensus but validates perfectly
            } else {
                60f  // No consensus, validates with warning
            }
            
            return VotingResult(
                consensusReading = best.second,
                confidence = baseConfidence,
                agreementLevel = AgreementLevel.NO_CONSENSUS,
                details = "No consensus. Using ${best.first}'s reading (${best.second}) as it passed validation."
            )
        }
        
        // Nothing is valid
        return VotingResult(
            consensusReading = null,
            confidence = 0f,
            agreementLevel = AgreementLevel.NO_CONSENSUS,
            details = "No detector produced a valid reading"
        )
    }
    
    // ===== FAILURE TRACKING =====
    
    /**
     * Tracks consecutive failures to determine when to suggest manual input.
     */
    class FailureTracker {
        private var consecutiveFailures = 0
        private var totalAttempts = 0
        private val failureHistory = ArrayDeque<ErrorCode>(10)
        
        companion object {
            const val MAX_FAILURES_BEFORE_MANUAL = 15  // ~3 seconds at 5fps
            const val MAX_TOTAL_ATTEMPTS = 50         // ~10 seconds total
        }
        
        fun recordSuccess() {
            consecutiveFailures = 0
        }
        
        fun recordFailure(errorCode: ErrorCode) {
            consecutiveFailures++
            totalAttempts++
            failureHistory.addLast(errorCode)
            if (failureHistory.size > 10) {
                failureHistory.removeFirst()
            }
        }
        
        fun reset() {
            consecutiveFailures = 0
            totalAttempts = 0
            failureHistory.clear()
        }
        
        fun shouldSuggestManualInput(): Boolean {
            return consecutiveFailures >= MAX_FAILURES_BEFORE_MANUAL ||
                   totalAttempts >= MAX_TOTAL_ATTEMPTS
        }
        
        fun getMostCommonError(): ErrorCode? {
            return failureHistory.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        }
        
        fun getFailureStats(): String {
            return "Consecutive: $consecutiveFailures, Total: $totalAttempts, Common: ${getMostCommonError()}"
        }
    }
}
