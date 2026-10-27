package com.jixiexiaoge.drivingassist.ui.components

internal fun exceedsDisplayedLimit(speedKph: Double?, limitKph: Int?): Boolean? {
    if (speedKph == null || !speedKph.isFinite() || speedKph !in 0.0..300.0 ||
        limitKph == null || limitKph !in 1..249) return null
    return speedKph > limitKph
}
