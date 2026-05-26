package com.deskpet.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.deskpet.data.model.PetInfo
import com.deskpet.data.model.PetType
import org.junit.Rule
import org.junit.Test

class PetListScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setPetListContent(
        petList: List<PetInfo> = emptyList(),
        activePetId: String? = null,
        isOverlayRunning: Boolean = false,
        curScale: Float = 1.0f
    ) {
        composeTestRule.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                PetListScreen(
                    petList = petList,
                    activePetId = activePetId,
                    isOverlayRunning = isOverlayRunning,
                    curScale = curScale,
                    onPetSelected = {},
                    onStartOverlay = {},
                    onStopOverlay = {},
                    onImportClick = {},
                    onDeletePet = {},
                    onScaleChange = {},
                    onExportPet = {},
                    onBackupAll = {}
                )
            }
        }
    }

    @Test
    fun rendersTitleText() {
        setPetListContent()
        composeTestRule.onNodeWithText("宠物栏").assertIsDisplayed()
    }

    @Test
    fun rendersImportButton() {
        setPetListContent()
        composeTestRule.onNodeWithText("导入").assertIsDisplayed()
    }

    @Test
    fun showsEmptyStateWhenNoPets() {
        setPetListContent()
        composeTestRule.onNodeWithText("还没有宠物").assertIsDisplayed()
    }

    @Test
    fun rendersStartButtonWhenActivePetExistsAndOverlayNotRunning() {
        val activePet = PetInfo(
            id = "pet-1",
            type = PetType.CODEX_PET,
            displayName = "TestPet",
            spritesheetPath = "/fake/test.png",
            isActive = true
        )
        setPetListContent(petList = listOf(activePet), activePetId = "pet-1", isOverlayRunning = false)
        composeTestRule.onNodeWithText("开启").assertIsDisplayed()
    }

    @Test
    fun rendersStopButtonWhenOverlayRunning() {
        val activePet = PetInfo(
            id = "pet-1",
            type = PetType.CODEX_PET,
            displayName = "TestPet",
            spritesheetPath = "/fake/test.png",
            isActive = true
        )
        setPetListContent(petList = listOf(activePet), activePetId = "pet-1", isOverlayRunning = true)
        composeTestRule.onNodeWithText("关闭").assertIsDisplayed()
    }
}
