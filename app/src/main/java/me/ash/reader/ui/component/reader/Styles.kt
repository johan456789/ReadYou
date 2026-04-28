/*
 * Feeder: Android RSS reader app
 * https://gitlab.com/spacecowboy/Feeder
 *
 * Copyright (C) 2022  Jonas Kalderstam
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.ash.reader.ui.component.reader

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.ash.reader.infrastructure.preference.*

val MediumContentWidth = 600.dp
val ExpandedContentWidth = 768.dp

val LocalTextContentWidth = compositionLocalOf { MediumContentWidth }

@Stable
@Composable
@ReadOnlyComposable
fun onSurfaceColor(): Color = MaterialTheme.colorScheme.onSurface

@Stable
@Composable
@ReadOnlyComposable
fun onSurfaceVariantColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant

@Stable
@Composable
@ReadOnlyComposable
fun textHorizontalPadding(): Int = LocalReadingTextHorizontalPadding.current

@Stable @Composable @ReadOnlyComposable fun bodyForeground(): Color = onSurfaceVariantColor()

@Stable
@Composable
@ReadOnlyComposable
fun bodyStyle(): TextStyle =
    LocalTextStyle.current.merge(
        fontFamily = LocalReadingFonts.current.asFontFamily(LocalContext.current),
        fontWeight =
            if (LocalReadingTextBold.current.value) FontWeight.SemiBold else FontWeight.Normal,
        fontSize = LocalReadingTextFontSize.current.sp,
        letterSpacing = LocalReadingTextLetterSpacing.current.sp,
        color = bodyForeground(),
        textAlign = LocalReadingTextAlign.current.toTextAlign(),
    )

@Stable
@Composable
@ReadOnlyComposable
fun h3Style(): TextStyle =
    MaterialTheme.typography.headlineSmall.merge(
        fontFamily = LocalReadingFonts.current.asFontFamily(LocalContext.current),
        fontWeight =
            if (LocalReadingSubheadBold.current.value) FontWeight.SemiBold else FontWeight.Normal,
        letterSpacing = 0.sp,
        color = onSurfaceColor(),
        textAlign = LocalReadingSubheadAlign.current.toTextAlign(),
    )
