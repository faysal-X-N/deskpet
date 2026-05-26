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

    override suspend fun updatePetList(list: List<PetInfo>) {
        _petList.value = list
    }

    override suspend fun setActivePetId(id: String) {
        _activePetId.value = id
    }

    override suspend fun setFirstLaunchDone() {
        _isFirstLaunch.value = false
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
}
