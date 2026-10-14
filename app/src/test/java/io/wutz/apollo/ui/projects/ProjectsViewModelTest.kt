package io.wutz.apollo.ui.projects

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.wutz.apollo.R
import io.wutz.apollo.data.local.DataScope
import io.wutz.apollo.data.model.DiscoveredRepo
import io.wutz.apollo.data.model.ProjectFolder
import io.wutz.apollo.data.model.ProjectInfo
import io.wutz.apollo.data.ws.ChangeEvents
import io.wutz.apollo.data.ws.ProjectsRepository
import io.wutz.apollo.data.ws.ProjectsResult
import io.wutz.apollo.data.ws.WsEvent
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<ProjectsRepository>()

    private val app =
        ProjectInfo(id = "p_1", name = "App", folders = listOf(ProjectFolder("/code/app", isPrimary = true)))
    private val old = ProjectInfo(id = "p_2", name = "Old", archived = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.list() } returns ProjectsResult.Ok(listOf(app, old))
        coEvery { repo.discoveredRepos() } returns emptyList()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() =
        ProjectsViewModel(
            repository = repo,
            scopes = MutableStateFlow<DataScope?>(null),
            events = emptyFlow<WsEvent>(),
        )

    @Test
    fun activeAndArchivedFiltersSplitTheList() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            assertEquals(
                listOf("p_1"),
                vm.uiState.value.visibleProjects
                    .map { it.id },
            )
            vm.setFilter(ProjectsFilter.ARCHIVED)
            assertEquals(
                listOf("p_2"),
                vm.uiState.value.visibleProjects
                    .map { it.id },
            )
        }

    @Test
    fun searchMatchesFolderPaths() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.setQuery("code/app")
            assertEquals(
                listOf("p_1"),
                vm.uiState.value.visibleProjects
                    .map { it.id },
            )
        }

    @Test
    fun blankNameIsRejectedWithoutCallingTheGateway() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.save(ProjectDraft(name = "  "))
            assertEquals(UiText.res(R.string.projects_error_name_required), vm.uiState.value.editorError)
            coVerify(exactly = 0) { repo.create(any(), any(), any()) }
        }

    @Test
    fun createFailureKeepsEditorOpenWithGatewayMessage() =
        runTest(dispatcher) {
            coEvery { repo.create("Dup", "/code/app", null) } returns ProjectsResult.Failed("folder already belongs")
            val vm = viewModel()
            advanceUntilIdle()
            vm.startCreate()
            vm.save(ProjectDraft(name = "Dup", folder = " /code/app "))
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.editor)
            assertEquals(UiText.raw("folder already belongs"), vm.uiState.value.editorError)
        }

    @Test
    fun editSendsUpdateAndClosesEditor() =
        runTest(dispatcher) {
            coEvery { repo.update("p_1", "Renamed", "", "hsl(0 68% 58%)") } returns ProjectsResult.Ok(Unit)
            val vm = viewModel()
            advanceUntilIdle()
            vm.startEdit(app)
            vm.save(
                vm.uiState.value.editor!!
                    .copy(name = "Renamed", color = "hsl(0 68% 58%)"),
            )
            advanceUntilIdle()
            assertNull(vm.uiState.value.editor)
            coVerify { repo.update("p_1", "Renamed", "", "hsl(0 68% 58%)") }
        }

    @Test
    fun deleteRunsOnlyAfterConfirm() =
        runTest(dispatcher) {
            coEvery { repo.delete("p_1") } returns ProjectsResult.Ok(Unit)
            val vm = viewModel()
            advanceUntilIdle()
            vm.requestDelete(app)
            coVerify(exactly = 0) { repo.delete(any()) }
            vm.confirmDelete()
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.delete("p_1") }
            assertNull(vm.uiState.value.deleteTarget)
        }

    @Test
    fun failedMutationSurfacesToast() =
        runTest(dispatcher) {
            coEvery { repo.setArchived("p_1", true) } returns ProjectsResult.Failed("no such project")
            val vm = viewModel()
            advanceUntilIdle()
            vm.setArchived(app, true)
            advanceUntilIdle()
            assertEquals(UiText.Failure(UiText.raw("no such project")), vm.uiState.value.toastMessage)
            assertNull(vm.uiState.value.toastUndo)
            assertNull(vm.uiState.value.busyId)
        }

    @Test
    fun archiveOffersUndoThatRestoresTheProject() =
        runTest(dispatcher) {
            coEvery { repo.setArchived("p_1", any()) } returns ProjectsResult.Ok(Unit)
            val vm = viewModel()
            advanceUntilIdle()
            vm.setArchived(app, true)
            advanceUntilIdle()
            assertEquals(UiText.res(R.string.projects_msg_archived), vm.uiState.value.toastMessage)
            val undo = vm.uiState.value.toastUndo
            assertEquals(ProjectArchiveUndo("p_1", archived = false), undo)

            vm.clearToast()
            vm.undoArchive(undo!!)
            advanceUntilIdle()
            coVerify { repo.setArchived("p_1", false) }
            assertEquals(UiText.res(R.string.projects_msg_restored), vm.uiState.value.toastMessage)
            assertNull(vm.uiState.value.toastUndo)
        }

    @Test
    fun suggestionsHideReposAlreadyInAProject() =
        runTest(dispatcher) {
            coEvery { repo.discoveredRepos() } returns
                listOf(
                    DiscoveredRepo("/code/app", "app", 3),
                    DiscoveredRepo("/opt/monit", "monit", 2),
                )
            val vm = viewModel()
            advanceUntilIdle()
            assertEquals(
                listOf("/opt/monit"),
                vm.uiState.value.suggestions
                    .map { it.root },
            )
        }

    @Test
    fun projectsChangedEventRefetchesWithoutSpinner() =
        runTest(dispatcher) {
            val events = MutableSharedFlow<WsEvent>()
            val vm =
                ProjectsViewModel(
                    repository = repo,
                    scopes = MutableStateFlow<DataScope?>(null),
                    events = events,
                )
            advanceUntilIdle()
            val renamed = app.copy(name = "Renamed")
            coEvery { repo.list() } returns ProjectsResult.Ok(listOf(renamed, old))

            events.emit(WsEvent.ChangeEvent(ChangeEvents.PROJECTS))
            assertFalse(vm.uiState.value.isLoading)
            advanceUntilIdle()

            assertEquals(
                "Renamed",
                vm.uiState.value.projects
                    .first { it.id == "p_1" }
                    .name,
            )
            coVerify(exactly = 2) { repo.list() }
        }

    @Test
    fun failedProjectsChangedRefetchKeepsTheList() =
        runTest(dispatcher) {
            val events = MutableSharedFlow<WsEvent>()
            val vm =
                ProjectsViewModel(
                    repository = repo,
                    scopes = MutableStateFlow<DataScope?>(null),
                    events = events,
                )
            advanceUntilIdle()
            coEvery { repo.list() } returns ProjectsResult.Failed("boom")

            events.emit(WsEvent.ChangeEvent(ChangeEvents.PROJECTS))
            advanceUntilIdle()

            assertEquals(
                listOf("p_1", "p_2"),
                vm.uiState.value.projects
                    .map { it.id },
            )
            assertNull(vm.uiState.value.errorMessage)
        }
}
