@file:OptIn(ExperimentalTextApi::class)

package com.swarmos.app.ui

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.swarmos.app.R

// All four families are variable TTFs bundled in res/font (SIL OFL, see res/font/README.txt).
// Each weight is one instance of the variable font, so no weight is ever synthesised.

private fun bricolage(weight: Int) = Font(
    R.font.bricolage_grotesque,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.Setting("opsz", 28f), // optical size: titles are shown at 20 to 28 sp
        FontVariation.Setting("wdth", 100f),
    ),
)

private fun geist(weight: Int) = Font(
    R.font.geist,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private fun geistMono(weight: Int) = Font(
    R.font.geist_mono,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private fun shantell(weight: Int) = Font(
    R.font.shantell_sans,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.Setting("INFM", 60f), // informality: a person wrote this, not a font
        FontVariation.Setting("BNCE", 0f),
        FontVariation.Setting("SPAC", 0f),
    ),
)

val BricolageFamily = FontFamily(bricolage(600), bricolage(700), bricolage(800))
val GeistFamily = FontFamily(geist(400), geist(500), geist(600), geist(700))
val GeistMonoFamily = FontFamily(geistMono(400), geistMono(500), geistMono(600), geistMono(700))
val ShantellFamily = FontFamily(shantell(400), shantell(500), shantell(600))
