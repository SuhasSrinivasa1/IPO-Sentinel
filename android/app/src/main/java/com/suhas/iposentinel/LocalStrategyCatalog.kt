package com.suhas.iposentinel

/**
 * Static fallback only. Runtime strategy statistics come from StrategyEvidenceStore.
 */
object LocalStrategyCatalog {
    fun summary(): StrategySummary {
        val families = CompositeStrategyCatalog.definitions.map { definition ->
            StrategyFamilyStats(
                familyId = definition.id,
                name = definition.name,
                phase = definition.phase,
                description = definition.description + " • " + definition.ingredients.joinToString(" + "),
                trades = 0,
                winRatePct = 0.0,
                expectancyBps = 0.0,
                profitFactor = 0.0,
                maxDrawdownBps = 0.0,
                last20NetBps = 0.0,
                status = "LEARNING",
                rankingScore = 0.0
            )
        }
        return StrategySummary(
            totalStrategyFamilies = families.size,
            testedFamilies = 0,
            champions = 0,
            challengers = 0,
            untestedFamilies = families.size,
            topFive = families,
            families = families,
            rankingNote = "Five composite strategies are defined. They are not labeled working until shadow replay produces enough evidence."
        )
    }
}
