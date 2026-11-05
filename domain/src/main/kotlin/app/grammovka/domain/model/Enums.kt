package app.grammovka.domain.model

/** State of the food the nutrients refer to. All nutrients are per 100 g (drinks: per 100 ml ≈ 100 g). */
enum class FoodBasis { RAW, COOKED, AS_SOLD }

/** Where catalog nutrient values came from (domain invariant 1). */
enum class FoodSource { LABEL, OFF, USDA, WEB, CLAUDE_ESTIMATE, MANUAL, RECIPE }

enum class Confidence { HIGH, MEDIUM, LOW }

enum class InputMethod { TEXT, VOICE, BARCODE, LABEL, PHOTO, QUICK, TEMPLATE }

/** Manual weigh-ins win over imported ones for the same date. */
enum class WeightSource { MANUAL, HEALTH_CONNECT }

enum class AiPurpose { PARSE, RESOLVE, LABEL, MEAL_PHOTO, ANALYSIS, CHAT }
