package io.github.mangi.eta.ui.screens.settings

/** Validate the entire draft; never strip invalid characters or clamp an overflowing value. */
internal fun parseRequestCompactionIntervalInput(input: String): Int? {
    if (input.isEmpty() || input.any { it !in '0'..'9' }) return null
    return input.toIntOrNull()?.takeIf { it > 0 }
}
