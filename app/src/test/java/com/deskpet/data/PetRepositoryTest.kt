package com.deskpet.data

import com.deskpet.data.model.PetInfo
import com.deskpet.data.model.PetType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

// ── In-memory fake for testing ──

class InMemoryPetStore : IPetStore {
    private val _petList = MutableStateFlow<List<PetInfo>>(emptyList())
    override val petListFlow: Flow<List<PetInfo>> = _petList.asStateFlow()

    private val _activePetId = MutableStateFlow<String?>(null)
    override val activePetIdFlow: Flow<String?> = _activePetId.asStateFlow()

    private val _isFirstLaunch = MutableStateFlow(true)
    override val isFirstLaunch: Flow<Boolean> = _isFirstLaunch.asStateFlow()

    private val _samplesLoaded = MutableStateFlow(false)
    override val samplesLoaded: Flow<Boolean> = _samplesLoaded.asStateFlow()

    private val _overlayAutoResume = MutableStateFlow(false)
    override val overlayAutoResumeFlow: Flow<Boolean> = _overlayAutoResume.asStateFlow()

    override suspend fun updatePetList(list: List<PetInfo>) {
        _petList.value = list
    }

    override suspend fun setActivePetId(id: String) {
        _activePetId.value = id
    }

    override suspend fun setFirstLaunchDone() {
        _isFirstLaunch.value = false
    }

    override suspend fun setSamplesLoaded() {
        _samplesLoaded.value = true
    }

    override suspend fun setOverlayAutoResume(v: Boolean) {
        _overlayAutoResume.value = v
    }
}

// ── Helper ──

fun testPet(
    id: String = "",
    displayName: String = "TestPet",
    sourceId: String = "",
    isActive: Boolean = false
): PetInfo = PetInfo(
    id = id,
    type = PetType.CODEX_PET,
    displayName = displayName,
    spritesheetPath = "/fake/test.png",
    isActive = isActive,
    sourceId = sourceId
)

// ── Tests ──

@OptIn(ExperimentalCoroutinesApi::class)
class PetRepositoryTest {

    private lateinit var repo: PetRepository
    private lateinit var store: InMemoryPetStore

    @Before
    fun setUp() {
        store = InMemoryPetStore()
        repo = PetRepository(store)
    }

    @Test
    fun `addPet generates UUID`() = runTest {
        val pet = testPet()
        val result = repo.addPet(pet)

        assertNotNull(result.id)
        assertTrue(result.id.isNotEmpty())
        // verify it's a valid UUID
        assertNotNull(UUID.fromString(result.id))
        // verify pet was stored
        val pets = store.petListFlow.first()
        assertEquals(1, pets.size)
        assertEquals(result.id, pets[0].id)
    }

    @Test
    fun `deletePet removes pet from list`() = runTest {
        val pet = repo.addPet(testPet())

        repo.deletePet(pet.id)

        val pets = store.petListFlow.first()
        assertTrue(pets.none { it.id == pet.id })
    }

    @Test
    fun `deletePet clears active pet when active is deleted`() = runTest {
        val pet = repo.addPet(testPet())
        repo.setActivePet(pet.id)

        repo.deletePet(pet.id)

        val activeId = store.activePetIdFlow.first()
        assertEquals("", activeId)
    }

    @Test
    fun `deletePet does not clear active when different pet is deleted`() = runTest {
        val pet1 = repo.addPet(testPet(displayName = "Pet1"))
        val pet2 = repo.addPet(testPet(displayName = "Pet2"))
        repo.setActivePet(pet1.id)

        repo.deletePet(pet2.id)

        val activeId = store.activePetIdFlow.first()
        assertEquals(pet1.id, activeId)
    }

    @Test
    fun `setActivePet updates isActive flag`() = runTest {
        val pet1 = repo.addPet(testPet(displayName = "Pet1"))
        val pet2 = repo.addPet(testPet(displayName = "Pet2"))

        repo.setActivePet(pet1.id)

        val pets = store.petListFlow.first()
        val activePet = pets.find { it.id == pet1.id }
        val inactivePet = pets.find { it.id == pet2.id }

        assertTrue(activePet?.isActive == true)
        assertFalse(inactivePet?.isActive == true)

        // verify activePetId was set
        val activeId = store.activePetIdFlow.first()
        assertEquals(pet1.id, activeId)
    }

    @Test
    fun `setActivePet flips active from one pet to another`() = runTest {
        val pet1 = repo.addPet(testPet(displayName = "Pet1"))
        val pet2 = repo.addPet(testPet(displayName = "Pet2"))
        repo.setActivePet(pet1.id)

        repo.setActivePet(pet2.id)

        val pets = store.petListFlow.first()
        assertFalse(pets.find { it.id == pet1.id }?.isActive == true)
        assertTrue(pets.find { it.id == pet2.id }?.isActive == true)
        assertEquals(pet2.id, store.activePetIdFlow.first())
    }

    @Test
    fun `getPet returns correct pet`() = runTest {
        val added = repo.addPet(testPet(displayName = "FindMe"))

        val found = repo.getPet(added.id)

        assertNotNull(found)
        assertEquals(added.id, found!!.id)
        assertEquals("FindMe", found.displayName)
    }

    @Test
    fun `getPet returns null for non-existent id`() = runTest {
        val found = repo.getPet("non-existent-id")
        assertNull(found)
    }

    @Test
    fun `existsBySourceId correctly detects duplicates`() = runTest {
        repo.addPet(testPet(sourceId = "unique-1"))

        // positive match
        assertTrue(repo.existsBySourceId("unique-1"))
        // negative match
        assertFalse(repo.existsBySourceId("unique-2"))
    }

    @Test
    fun `existsBySourceId returns false for empty string`() = runTest {
        // empty sourceId should short-circuit
        assertFalse(repo.existsBySourceId(""))
    }

    @Test
    fun `addPet appends to existing list`() = runTest {
        repo.addPet(testPet(displayName = "First"))
        repo.addPet(testPet(displayName = "Second"))

        val pets = store.petListFlow.first()
        assertEquals(2, pets.size)
        assertEquals("First", pets[0].displayName)
        assertEquals("Second", pets[1].displayName)
    }

    // ===== addPetDirect：示例加载 / 备份恢复入口（必须幂等）=====

    @Test
    fun `addPetDirect is idempotent for the same id`() = runTest {
        // 首次启动加载 assets 时旋转屏幕，重建的 LaunchedEffect 会再跑一遍；
        // 备份恢复也可能遇到已存在的 id。重复写入会让列表出现两个同 id 项，
        // LazyVerticalGrid(items(key = { it.id })) 直接抛 "Key was already used"
        val pet = testPet(id = "jutuan", sourceId = "jutuan")
        repo.addPetDirect(pet)
        repo.addPetDirect(pet)

        val pets = store.petListFlow.first()
        assertEquals(1, pets.size)
        assertEquals("jutuan", pets[0].id)
    }

    @Test
    fun `addPetDirect keeps original id and insertion order`() = runTest {
        repo.addPetDirect(testPet(id = "a", displayName = "A"))
        repo.addPetDirect(testPet(id = "b", displayName = "B"))

        val pets = store.petListFlow.first()
        assertEquals(listOf("a", "b"), pets.map { it.id })
        assertEquals(listOf("A", "B"), pets.map { it.displayName })
    }
}
