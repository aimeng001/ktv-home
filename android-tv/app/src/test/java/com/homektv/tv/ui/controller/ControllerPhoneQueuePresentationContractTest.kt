package com.homektv.tv.ui.controller

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerPhoneQueuePresentationContractTest {
    @Test
    fun queuePanelDistinguishesLoadingErrorSuccessfulEmptyAndExistingRows() {
        val source = locate("src/main/java/com/homektv/tv/ui/controller/ControllerFragment.kt").readText()

        assertTrue(source.contains("state.queueLoading"))
        assertTrue(source.contains("state.errorFor(UiDomain.QUEUE)"))
        assertTrue(source.contains("队列加载失败"))
        assertTrue(source.contains("队列里还没有歌曲"))
        assertTrue(source.contains("正在加载队列"))
        assertTrue(source.contains("重试队列"))
        assertTrue(source.contains("去选歌"))
        assertTrue(source.contains("viewModel.refreshQueue()"))
        assertTrue(source.contains("showPhonePanel(ControllerPhonePanel.SEARCH)"))
    }

    private fun locate(relativePath: String): File {
        val workingDirectory = File(System.getProperty("user.dir").orEmpty())
        return sequenceOf(
            workingDirectory.resolve(relativePath),
            workingDirectory.resolve("app/$relativePath"),
            workingDirectory.resolve("../$relativePath"),
            workingDirectory.resolve("../../$relativePath"),
        ).firstOrNull(File::isFile) ?: error("Cannot locate $relativePath")
    }
}
