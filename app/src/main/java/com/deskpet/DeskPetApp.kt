package com.deskpet

import android.app.Application
import com.deskpet.data.PetRepository
import com.deskpet.data.PetStore

class DeskPetApp : Application() {
    val petRepository: PetRepository by lazy { PetRepository(PetStore(this)) }
}

