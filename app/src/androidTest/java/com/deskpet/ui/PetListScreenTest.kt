package com.deskpet.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.deskpet.data.model.PetInfo
import com.deskpet.data.model.PetType
import org.junit.Rule
import org.junit.Test

/**
 * 列表页 UI 回归测试。
 *
 * 注意：本文件的断言文本必须与 PetListScreen 的真实文案逐字一致。历史上这里
 * 断言过 "导入"/"开启"/"关闭"，而 UI 早已改成 "导入宠物"/"开启宠物"/"关闭宠物"，
 * 又因为 CI 从不编译 androidTest，编译错误（漏传 isLoading）与文案漂移一起潜伏了
 * 很久。现在 CI 增加 assembleDebugAndroidTest，两者都会被当场拦下。
 */
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
                    isLoading = false,
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

    private fun samplePet() = PetInfo(
        id = "pet-1",
        type = PetType.CODEX_PET,
        displayName = "TestPet",
        spritesheetPath = "/fake/test.png",
        isActive = true
    )

    @Test
    fun rendersTitleText() {
        setPetListContent()
        composeTestRule.onNodeWithText("宠物栏").assertIsDisplayed()
    }

    @Test
    fun rendersImportButton() {
        setPetListContent()
        // 空状态里的主按钮是「导入宠物」（不是「导入」，后者只在展开后的收纳坞里）
        composeTestRule.onNodeWithText("导入宠物").assertIsDisplayed()
    }

    @Test
    fun showsEmptyStateWhenNoPets() {
        setPetListContent()
        composeTestRule.onNodeWithText("还没有宠物").assertIsDisplayed()
    }

    @Test
    fun rendersStartButtonWhenActivePetExistsAndOverlayNotRunning() {
        setPetListContent(petList = listOf(samplePet()), activePetId = "pet-1", isOverlayRunning = false)
        // 开启/关闭按钮位于收纳坞展开面板内，先展开圆钮
        composeTestRule.onNodeWithContentDescription("展开控制").performClick()
        composeTestRule.onNodeWithText("开启宠物").assertIsDisplayed()
    }

    @Test
    fun rendersStopButtonWhenOverlayRunning() {
        setPetListContent(petList = listOf(samplePet()), activePetId = "pet-1", isOverlayRunning = true)
        composeTestRule.onNodeWithContentDescription("展开控制").performClick()
        composeTestRule.onNodeWithText("关闭宠物").assertIsDisplayed()
    }
}
