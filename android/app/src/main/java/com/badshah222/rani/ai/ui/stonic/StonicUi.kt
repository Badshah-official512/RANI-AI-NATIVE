package com.badshah222.rani.ai.ui.stonic

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badshah222.rani.ai.R
import com.badshah222.rani.ai.ui.theme.*

/**
 * The Stonic design system, adapted to a phone portrait scrolling page
 * (Stonic's desktop window is 3 fixed columns; RANI stacks the same panels
 * vertically so every join stays visible while scrolling).
 *
 * Source: STONIC-FULL-DETAILS.md §11 — top bar (Floating + Awareness),
 * Four Core Circuits (MEMORY cyan / SKILLS green / CHARACTER red /
 * SETTING grey, each joined to the figure by its colour's connector line),
 * Visual Hub ("What do you want to see?"), Your World (globe + "Three
 * things for you today"), VOICE | NOTES panel (SYSTEM READY, mic),
 * dark theme, holographic figure character.
 *
 * Character rule: the faceless glowing holographic girl-form is ALWAYS
 * shown full-figure (figure-shaped, never circular). Small avatar spots
 * use the head/shoulders crop in a rounded rectangle — never a circle,
 * never a plain glowing ball (a round crop hides the figure).
 */

/** Four Core Circuits — Stonic's exact colours. */
val CircuitMemory = Color(0xFF22D3EE)    // cyan
val CircuitSkills = Color(0xFF4ADE80)    // green
val CircuitCharacter = Color(0xFFF87171) // red
val CircuitSetting = Color(0xFF9CA3AF)   // grey

/** The character, head + shoulders crop, rounded rectangle — never a circle. */
@Composable
fun RaniAvatar(size: Dp, corner: Dp = 12.dp, modifier: Modifier = Modifier) {
    Image(
        painterResource(R.drawable.rani_character),
        contentDescription = "RANI",
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(RoundedCornerShape(corner)),
    )
}

/** One Core Circuit chip: coloured dot + caps label. */
@Composable
fun CircuitChip(label: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Panel2)
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.2.sp)
    }
}

/**
 * Four Core Circuits panel: 2x2 chips with coloured connector lines that
 * converge upward toward the character figure above the panel.
 */
@Composable
fun CircuitsPanel(onCircuit: (String) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(148.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val top = Offset(w / 2f, 4.dp.toPx())
            val lines = listOf(
                Triple(Offset(w * 0.25f, 84.dp.toPx()), CircuitMemory, 0.25f),
                Triple(Offset(w * 0.75f, 84.dp.toPx()), CircuitSkills, 0.75f),
                Triple(Offset(w * 0.25f, 140.dp.toPx()), CircuitCharacter, 0.25f),
                Triple(Offset(w * 0.75f, 140.dp.toPx()), CircuitSetting, 0.75f),
            )
            lines.forEach { (from, color, _) ->
                drawLine(color.copy(alpha = 0.75f), from, top, strokeWidth = 2.dp.toPx())
                drawCircle(color.copy(alpha = 0.9f), radius = 3.dp.toPx(), center = from)
            }
            drawCircle(Color.White, radius = 4.dp.toPx(), center = top)
            drawCircle(CircuitMemory, radius = 7.dp.toPx(), center = top, alpha = 0.35f)
        }
        Column(Modifier.fillMaxSize().padding(top = 52.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircuitChip("MEMORY", CircuitMemory, { onCircuit("memory") }, Modifier.weight(1f))
                CircuitChip("SKILLS", CircuitSkills, { onCircuit("tools") }, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircuitChip("CHARACTER", CircuitCharacter, { onCircuit("voicemode") }, Modifier.weight(1f))
                CircuitChip("SETTING", CircuitSetting, { onCircuit("settings") }, Modifier.weight(1f))
            }
        }
    }
}

/** A Stonic panel: dark card with a small caps title row. */
@Composable
fun StonicSection(
    title: String,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    action: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Panel)
            .border(1.dp, VioletSoft.copy(alpha = 0.22f), RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = VioletSoft, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                modifier = Modifier.weight(1f),
            )
            if (action != null) action()
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/** Stonic's status bar under the character ("Opening the voice channel…"). */
@Composable
fun StonicStatusBar(text: String, accent: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Panel2)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(accent))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
}
