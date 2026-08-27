package com.rubion.nexplaybe

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class NexplayBeApplication

fun main(args: Array<String>) {
    runApplication<NexplayBeApplication>(*args)
}
