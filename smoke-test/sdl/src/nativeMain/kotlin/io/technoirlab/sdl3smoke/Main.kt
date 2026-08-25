package io.technoirlab.sdl3smoke

import sdl3.SDL_GetVersion

fun main() {
    val version = SDL_GetVersion()
    check(version > 0) { "SDL_GetVersion failed" }
    println("SDL_GetVersion succeeded (version=$version)")
}
