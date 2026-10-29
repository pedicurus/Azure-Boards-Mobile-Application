package com.example.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test
import androidx.compose.ui.graphics.Color

class AzureBoardsColorsTest {

    @Test
    fun testParseAzureHexColor_NullOrBlank() {
        val typeName = "dummyTypeNullOrBlank"

        // Register null
        registerAzureTypeColor(typeName, null)
        assertEquals(AzureDevOpsBlue, getAzureTypeColor(typeName))

        // Register blank
        registerAzureTypeColor(typeName, "   ")
        assertEquals(AzureDevOpsBlue, getAzureTypeColor(typeName))
    }

    @Test
    fun testParseAzureHexColor_InvalidLength() {
        val typeName = "dummyTypeInvalidLength"

        // Register invalid length (5 characters)
        registerAzureTypeColor(typeName, "#12345")
        assertEquals(AzureDevOpsBlue, getAzureTypeColor(typeName))

        // Register invalid length (7 characters)
        registerAzureTypeColor(typeName, "#1234567")
        assertEquals(AzureDevOpsBlue, getAzureTypeColor(typeName))
    }

    @Test
    fun testParseAzureHexColor_InvalidHexCharacters() {
        val typeName = "dummyTypeInvalidHex"

        // Register invalid hex string to trigger NumberFormatException in try-catch
        registerAzureTypeColor(typeName, "#GHIJKL")
        assertEquals(AzureDevOpsBlue, getAzureTypeColor(typeName))
    }

    @Test
    fun testParseAzureHexColor_ValidColors() {
        // Register valid 6-char hex
        val typeName6 = "dummyTypeValid6"
        registerAzureTypeColor(typeName6, "#112233")
        assertEquals(Color(0xFF112233), getAzureTypeColor(typeName6))

        // Register valid 8-char hex
        val typeName8 = "dummyTypeValid8"
        registerAzureTypeColor(typeName8, "#88112233")
        assertEquals(Color(0x88112233), getAzureTypeColor(typeName8))

        // Register valid 6-char hex without prefix
        val typeName6NoPrefix = "dummyTypeValid6NoPrefix"
        registerAzureTypeColor(typeName6NoPrefix, "112233")
        assertEquals(Color(0xFF112233), getAzureTypeColor(typeName6NoPrefix))
    }
}
