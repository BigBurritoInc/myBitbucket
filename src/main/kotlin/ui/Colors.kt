package ui

import com.intellij.ui.JBColor
import java.awt.Color

// Same RGB in both themes: JBColor.GREEN read as too acidic/saturated against a light background.
// Shared so every "this succeeded" green in the plugin (PR card Approve/Merge buttons, the Cloud
// sign-in status label) reads as the same color. docs: Known incidents
val APPROVE_GREEN: JBColor = JBColor(Color(86, 167, 106), Color(86, 167, 106))
