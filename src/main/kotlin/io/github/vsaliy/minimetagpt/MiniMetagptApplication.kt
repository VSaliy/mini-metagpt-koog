package io.github.vsaliy.minimetagpt

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class MiniMetagptApplication

fun main(args: Array<String>) {
    runApplication<MiniMetagptApplication>(*args)
}
