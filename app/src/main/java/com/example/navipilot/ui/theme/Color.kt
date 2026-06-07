package com.example.navipilot.ui.theme

import androidx.compose.ui.graphics.Color

// Legacy colors for Material Components theme compatibility
val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)
val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

// ============================================
// Professional Design System - Color Palette
// ============================================

// Primary Colors - Professional Blue
val Primary = Color(0xFF2563EB)
val PrimaryVariant = Color(0xFF1D4ED8)
val PrimaryLight = Color(0xFF3B82F6)
val PrimaryDark = Color(0xFF1E40AF)

// Secondary Colors - Success Green
val Secondary = Color(0xFF10B981)
val SecondaryVariant = Color(0xFF059669)
val SecondaryLight = Color(0xFF22C55E)
val SecondaryDark = Color(0xFF047857)

// Accent Colors
val Accent = Color(0xFF06B6D4)       // Cyan - modern EV tech feel
val AccentOrange = Color(0xFFF97316)
val AccentPurple = Color(0xFF8B5CF6)
val AccentCyan = Color(0xFF06B6D4)

// Surface Colors - Neutral Scale (Dark Theme)
val Surface900 = Color(0xFF0A0E1A)    // Deep navy (not pure black, easier on eyes)
val Surface800 = Color(0xFF111827)    // Dark navy
val Surface700 = Color(0xFF1F2937)    // Slate
val Surface600 = Color(0xFF374151)    // Medium slate
val Surface500 = Color(0xFF64748B)    // Light slate
val Surface400 = Color(0xFF94A3B8)    // Very light slate
val Surface300 = Color(0xFFCBD5E1)    // Near white slate
val Surface200 = Color(0xFFE2E8F0)    // Off white
val Surface100 = Color(0xFFF1F5F9)    // Almost white
val Surface50 = Color(0xFFF8FAFC)     // Pure white-like

// Semantic Colors
val Success = Color(0xFF10B981)
val Warning = Color(0xFFF97316)
val Error = Color(0xFFEF4444)
val Info = Color(0xFF3B82F6)

// Text Colors (for dark backgrounds)
val TextPrimary = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFFCBD5E1)
val TextTertiary = Color(0xFF94A3B8)
val TextDisabled = Color(0xFF64748B)

// Speed Ring Colors
val SpeedCruise = Info              // Blue for cruise set speed
val SpeedCurrent = Success           // Green for current vehicle speed

// Control Button Colors
val ButtonHome = Color(0xFFFFD700)    // Gold for home
val ButtonCompany = Color(0xFFFF8C00) // Dark orange for company
val ButtonAdvanced = AccentOrange     // Orange for advanced functions

// Advanced Dialog Button Colors
val ButtonHelp = AccentCyan           // Cyan for help
val ButtonAccel = Color(0xFF10B981)   // Bright green for acceleration
val ButtonDecel = Error               // Red for deceleration
val ButtonLaneChange = PrimaryLight   // Blue for lane change
val ButtonReport = AccentPurple       // Purple for report
val ButtonExperiment = AccentOrange   // Orange for experiment

// Overtake Mode Colors
val OvertakeDisabled = Surface400     // Gray for disabled
val OvertakeManual = PrimaryLight     // Blue for manual
val OvertakeAuto = Success            // Green for auto

// Colors

// Card and Container Colors
val CardBackground = Color.White
val CardBackgroundDark = Surface800
val DialogBackground = Surface800
val OverlayBackground = Surface900.copy(alpha = 0.92f)