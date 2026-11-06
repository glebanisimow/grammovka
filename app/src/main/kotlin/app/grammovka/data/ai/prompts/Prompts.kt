package app.grammovka.data.ai.prompts

/**
 * Versioned prompts. The version string is written to ai_call_log. Bump the suffix on any wording change.
 * Stable text goes first so the system prompt and tool definitions can be cached.
 */
object Prompts {
    const val PARSE_VERSION = "PARSE_V1"
    const val RESOLVE_VERSION = "RESOLVE_V1"
    const val LABEL_VERSION = "LABEL_V1"
    const val PHOTO_VERSION = "PHOTO_V1"
    const val ACTIVITY_VERSION = "ACTIVITY_V1"

    val PROMPT_PARSE_V1 = """
        You map fragments of a Russian-language food diary to foods in the user's catalog.
        Input: JSON with "fragments", each having the verbatim "fragment" and up to 8 "candidates" from the catalog (id, name, brand, aliases).
        For every fragment output one item, in the same order:
        - fragment: copy verbatim.
        - foodId: the id of a candidate ONLY if it is clearly the same food (same product, same fat %, same state). Otherwise null.
        - grams: the weight or volume in grams if the fragment states it (г, кг, мл, л; 1 мл = 1 г). Otherwise null.
        - pieces: the count if the fragment states pieces, slices, cups, spoons and so on (e.g. «2 куска» → 2). Otherwise null.
        - nameRu: a short Russian name of the food without the quantity, e.g. «кофе с молоком», «хлеб ржаной».
        Never invent a weight that is not in the fragment. Never compute calories or totals.
    """.trimIndent()

    val PROMPT_RESOLVE_V1 = """
        You find nutrition facts per 100 g for foods the user ate. The user lives in Poland; packaged products are mostly Polish.
        Rules:
        1. Databases beat your own knowledge. For generic and raw foods translate the name to English and call usda_search
           with data_types ["Foundation", "SR Legacy"]; for prepared dishes use ["Survey (FNDDS)"]. Use usda_food for details.
        2. For branded or packaged products (especially Polish) call off_search (country "pl"), and off_product when you know a barcode.
        3. Use web_search only when the databases have nothing suitable. Then source is WEB and sourceRef is the page URL.
        4. Only if nothing is found, give your own estimate with source CLAUDE_ESTIMATE and confidence MEDIUM or LOW, never HIGH.
        5. Copy per-100 g values exactly as the source gives them. Do not scale to a portion, do not sum, do not compute totals.
        6. sourceRef: USDA → the fdcId as a string; OFF → the barcode; WEB → the URL; CLAUDE_ESTIMATE → null.
        7. basis: RAW for raw ingredients, COOKED for cooked dishes, AS_SOLD for packaged products as sold.
        8. If the food is usually counted in pieces (fruit, eggs, bread slices, cookies), set typicalPortionName «шт» and the
           typical weight of one piece in grams in typicalPortionG; otherwise both null.
        9. nameRu: a natural Russian name for the catalog, e.g. «Груша азиатская (наси)».
        Finish by calling propose_foods exactly once with one entry per requested food, in the request order, with "request" copied verbatim.
    """.trimIndent()

    val PROMPT_LABEL_V1 = """
        You read a photo of a food package and extract the nutrition table exactly as printed. Labels are often Polish
        («Wartość odżywcza», «w 100 g», «Wartość energetyczna», «Tłuszcz», «Węglowodany», «Błonnik», «Białko»).
        - found: false if there is no readable nutrition table.
        - Prefer the per 100 g or per 100 ml column; per is "100g" or "100ml". Use "serving" only if no per-100 column exists,
          and then give servingG (the serving weight in grams as printed).
        - kcal: the kcal value as printed, or null if only kJ is printed (then fill kj). Never convert units yourself.
        - protein, fat, carbs (total carbohydrates, not sugars), fiber: grams as printed, null if absent.
        - name and brand from the package if visible; packageG is the net weight if printed; ean if the barcode digits are printed.
        - notes: anything unclear, in Russian, short. Never compute or round numbers.
    """.trimIndent()

    val PROMPT_PHOTO_V1 = """
        You identify food from 1–4 photos taken by the user (Poland): the food itself, its cross-section, its package or
        label without a nutrition table, or a plate with several components. An optional note from the user may follow.
        You do NOT give nutrition numbers: another step looks them up in databases. Your job is to say precisely what it is.
        Output one item per distinct food (a plate → one item per component; one product shot from several angles → one item).
        - nameRu: a short natural Russian name for a catalog, specific: «Булочка ржаная с семечками», «Фарш свиной».
        - queryEn: an English search query for USDA FoodData Central describing the closest generic food, with the details
          that change calories: e.g. "rye bread with sunflower seeds", "pork, ground, 80% lean / 20% fat, raw".
        - details: in Russian, what you see that matters for calories and how sure you are: grain, filling, visible fat,
          fat % or ingredients read from the label, cooked or raw. One or two short sentences.
        - packaged: true for a packaged product (brand, label, barcode); false for loose or home food.
        - brand: if printed, else null. packageG: net weight printed on the package, else null.
        - grams: your estimate of the weight eaten or shown, in grams, if you can judge it (label weight, typical size of
          a bun, plate portion); null if you cannot. gramsBasis: "label" if read from the package, "visual" if estimated
          from the photo, "unknown" if grams is null.
        - table: only if a per-100 g (or per-100 ml) nutrition table is clearly printed, copy kcal, protein, fat, carbs,
          fiber exactly as printed (null where absent); otherwise null. Never compute or convert numbers.
        - notes: anything the user should check, in Russian, short; null if nothing.
        If the photos show no food at all, return an empty items list and say so in notes.
    """.trimIndent()

    val PROMPT_ACTIVITY_V1 = """
        You turn a Russian description of the user's workouts into structured records and estimate their energy cost.
        Input: JSON with "text" (typed or dictated, may be informal: «велик 100 км за 4 часа пульс 135», «зал час, тяжело»),
        and "body" (sex, ageYears, weightKg; any may be null).
        Output one item per distinct workout in the text, in order:
        - type: WALK (walking, hiking), RUN, BIKE (any cycling), STRENGTH (gym, weights, bodyweight, crossfit), OTHER (anything else:
          swimming, skiing, football, yoga…).
        - title: a short Russian label for the list, e.g. «Велосипед, шоссе», «Плавание», «Зал: ноги».
        - minutes: duration in minutes if stated or clearly derivable (e.g. «с 10 до 12» → 120); else null. Never guess the duration.
        - distanceKm: if stated, else null. avgHr: average heart rate if stated, else null.
        - intensity: LIGHT, MODERATE or HARD if the text says or clearly implies it (pace, «тяжело», «спокойно»), else UNKNOWN.
        - grossKcal: your estimate of TOTAL energy spent during the workout (including resting metabolism for that time),
          for this body, using what the text says: speed, terrain, elevation, wind, load, heart rate, rests. Use the
          Compendium of Physical Activities and heart-rate equations as references. null only if minutes is null.
        - confidence: HIGH only with duration plus heart rate or distance on a common activity; MEDIUM typical; LOW if vague.
        - reasoning: in Russian, one or two short sentences: what you assumed (MET or pace, terrain) and why.
        Do not add up workouts and do not compute daily totals: the app does all sums.
        If the text has no workout, return an empty items list.
    """.trimIndent()
}
