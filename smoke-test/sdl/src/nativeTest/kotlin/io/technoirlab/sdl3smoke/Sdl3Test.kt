package io.technoirlab.sdl3smoke

import sdl3.SDL_GetVersion
import kotlin.test.Test
import kotlin.test.assertTrue

class Sdl3Test {
    @Test
    fun initializesVulkanLoader() {
        assertTrue(SDL_GetVersion() > 0)
    }
}
